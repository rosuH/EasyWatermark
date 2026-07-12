import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
    // S4d-360: official Android-KMP library plugin (replaces com.android.library + androidTarget).
    // https://developer.android.com/kotlin/multiplatform/plugin
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // C4.3: Kotlin-bundled Compose compiler + Compose Multiplatform. The latter delivers the
    // multiplatform (incl. iOS) androidx.compose graphics/text/material3 artifacts the shared
    // commonMain renderer and the shared CMP UI route need. compose.runtime/compose.ui are allowed;
    // Material3 is pinned explicitly (S4d-360); compose-resources / compose.components are NOT
    // (CMP-9547 stays out of scope). On Android these map to androidx.compose (BOM-aligned via
    // :app's dependency substitution + enforcedPlatform).
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    // S4d-91: Room KMP toolchain proof — KSP (multiplatform) + Room Gradle plugin, applied to
    // :shared only. Proves Room codegen co-exists with compose.multiplatform + Android-KMP here;
    // the production templates path in :app stays on classic Android Room/KSP, untouched.
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    // S4d-247: explicit Kotlin serialization compiler plugin so :shared owns generation for
    // @Serializable route objects in commonMain. App-side plugin remains intact.
    alias(libs.plugins.kotlin.serialization)
}

/**
 * `:shared` — the KMP module (plan C4). Platform-neutral domain types + the watermark geometry core
 * in `commonMain`, compiling for Android + JVM(desktop) + iOS.
 *
 * C4.3 lineage unification: commonMain carries the multiplatform Compose graphics/text/runtime/
 * material3 types so the shared commonMain renderer and shared CMP UI route can be written against
 * them. On Android, CMP resolves to androidx.compose (BOM-aligned via :app's dependency substitution
 * + enforcedPlatform).
 */
kotlin {
    // S4d-360: android {} block is the AGP Android-KMP target (not androidTarget() + top-level android).
    android {
        namespace = "me.rosuh.easywatermark.shared"
        compileSdk = Apps.compileSdk
        minSdk = Apps.minSdk
        // S4d-366: Android host tests run platform-neutral commonTest only (pure models/geometry).
        // Compose ImageBitmap cell-raster lives in skikoTest (Desktop/iOS) — not on the host JVM.
        withHostTest {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop")
    // C5.1 (S4d-25): declare a `Shared` framework on both iOS targets so the iOS app target
    // (`iosApp/`) can link `:shared`. A dynamic framework (the canonical KMP template choice) is used
    // so the framework self-contains skiko's transitive system-framework links — the consuming app
    // just does `import Shared` + `-framework Shared`, and `embedAndSignAppleFrameworkForXcode` picks
    // the right CONFIGURATION/SDK/ARCH from Xcode's environment. This is framework PACKAGING wiring
    // only: no renderer logic, no new dependency, no commonMain/Android change.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // C4.3 compile witness deps (no renderer logic). `compose.ui` includes ui-graphics +
            // ui-text + ui-unit; on Android → androidx.compose.ui:* (BOM), on iOS/desktop → klibs.
            implementation(compose.runtime)
            implementation(compose.ui)
            // S4d-360: explicit JetBrains Material3 (latest published) — do NOT use deprecated
            // compose.material3 accessor (resolved material3:1.9.0 → foundation:1.9.1 skew).
            // See build/s4d359-foundation-skew.md.
            implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
            // S4d-74: KMP DataStore Preferences.
            implementation(libs.datastore)
            implementation(libs.datastore.preference)
            // S4d-92: production templates Room path in commonMain.
            implementation(libs.room.runtime)
            // S4d-247: kotlinx-serialization-json for @Serializable routes.
            implementation(libs.kotlinx.serialization.json)
        }
        // Pure / platform-neutral tests. Gate: :shared:commonPureTest → testAndroidHostTest.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // S4d-18: Skiko desktop runtime for the DESKTOP target's MAIN source set.
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // S4d-142: bundled SQLite driver for Desktop Room (must NOT reach :app).
                implementation(libs.sqlite.bundled)
            }
        }
        // S4d-231: bundled SQLite driver for iOS targets only.
        val iosArm64Main by getting {
            dependencies {
                implementation(libs.sqlite.bundled)
            }
        }
        val iosSimulatorArm64Main by getting {
            dependencies {
                implementation(libs.sqlite.bundled)
            }
        }
        // S4d-2 / S4d-366: Desktop tests include Compose cell-raster suite (Skiko backend).
        // Sources live under src/skikoTest (not a KMP intermediate — avoids hierarchy-template ban).
        val desktopTest by getting {
            kotlin.srcDir("src/skikoTest/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// S4d-366: same skikoTest sources on iOS leaf tests (ImageBitmap works on Native Skia).
// Not on androidHostTest — AGP host JVM cannot allocate Compose ImageBitmap/Bitmap.
afterEvaluate {
    listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { name ->
        kotlin.sourceSets.findByName(name)?.kotlin?.srcDir("src/skikoTest/kotlin")
    }
}

// S4d-366: named pure common-test gate (platform-neutral commonTest only; no Compose cell raster).
tasks.register("commonPureTest") {
    group = "verification"
    description =
        "Platform-neutral commonTest only via Android host (no Skiko/ImageBitmap cell-raster tests)."
    dependsOn("testAndroidHostTest")
}

// S4d-91: Room KSP must be registered for every target the proof DB compiles for.
// With Android-KMP plugin, the Android KSP configuration remains kspAndroid.
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

// S4d-91: Room schema export location (toolchain requirement). Schemas land under shared/schemas.
room {
    schemaDirectory("$projectDir/schemas")
}
