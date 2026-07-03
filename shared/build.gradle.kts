import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
    id(libs.plugins.android.library.get().pluginId)
    // C4.3: Kotlin-bundled Compose compiler + Compose Multiplatform. The latter delivers the
    // multiplatform (incl. iOS) androidx.compose graphics/text/material3 artifacts the shared
    // commonMain renderer and the shared CMP UI route need. compose.runtime/compose.ui/
    // compose.material3 are allowed; compose-resources / compose.components are NOT
    // (CMP-9547 stays out of scope). On Android these map to androidx.compose (BOM-aligned via
    // :app's dependency substitution + enforcedPlatform).
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    // S4d-91: Room KMP toolchain proof — KSP (multiplatform) + Room Gradle plugin, applied to
    // :shared only. Proves Room codegen co-exists with compose.multiplatform + android.library here;
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
 * material3 types (via the Compose Multiplatform plugin) so the shared commonMain renderer
 * (S4d-2+: `WatermarkCellComposer`, `TextRasterEnv`, etc.) and the shared CMP UI route
 * (S4d-236+: `ui/theme/Color.kt`, `ui/theme/Theme.kt`, future shared screens) can be written
 * against them. On Android, CMP resolves to androidx.compose (BOM-aligned via :app's dependency
 * substitution + enforcedPlatform); :app substitutes any org.jetbrains.compose.* nodes to
 * androidx so the Android runtime graph is single-lineage.
 */
kotlin {
    androidTarget {
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
            // ui-text + ui-unit; on Android → androidx.compose.ui:* 1.11.2, on iOS/desktop → klibs.
            implementation(compose.runtime)
            implementation(compose.ui)
            // S4d-236: Material3 components for the shared CMP UI route. On Android →
            // androidx.compose.material3 (the :app dependency substitution converts the group
            // org.jetbrains.compose.* → androidx.compose.* preserving the original version, then the
            // enforcedPlatform Compose BOM aligns each AndroidX artifact; material3 is on its own
            // AndroidX version line, NOT 1.11.2); on iOS/desktop → the CMP material3 klib
            // (Skiko-backed). Same CMP plugin accessor as compose.runtime/compose.ui — no version
            // bump, no catalog change.
            implementation(compose.material3)
            // S4d-74: KMP DataStore Preferences. `datastore` (base/datastore-core) supplies the
            // platform-neutral DataStoreFactory/Storage the commonMain helper builds on; the
            // `-preferences` artifact supplies Preferences + the Android store creation used in
            // androidMain. Both publish KMP artifacts (android/jvm/ios) at 1.2.1. No expect/actual,
            // no iOS/desktop store creation yet — Android creation lives in androidMain.
            implementation(libs.datastore)
            implementation(libs.datastore.preference)
            // S4d-92: production templates Room path (data/db/**, data/repo/TemplateRepository,
            // data/model/entity/Template) lives in commonMain. room-runtime is a real runtime
            // dependency (the database is instantiated on Android). It transits onto :app's runtime,
            // but :app already declares room-runtime, so no NEW payload — and crucially NO sqlite
            // driver artifact is added: Android stays in Room compatibility mode on the framework
            // SupportSQLite open-helper (no .setDriver), so no libsqliteJni.so/sqlite-framework ships.
            implementation(libs.room.runtime)
            // S4d-247: explicit kotlinx-serialization-json so :shared commonMain owns the
            // runtime for @Serializable route objects. App-side dependency remains intact.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // S4d-18: Skiko desktop runtime for the DESKTOP target's MAIN source set, so the desktop
        // watermark text renderer (`DesktopWatermarkTextRenderer`) can RENDER `composeTextCell`
        // offscreen (Skia-backed `ImageBitmap`) and AWT-encode it to PNG at runtime — the first
        // production-ish Desktop use of the bundled commonMain text path (S4d-17 Option C: Android
        // text stays native; commonMain text is Desktop/iOS-first). DESKTOP (jvm) target ONLY — KMP
        // keeps it off the Android target, so it does NOT reach `:app` (`:app` consumes `:shared`'s
        // android variant; the dependency proof asserts 0 skiko in `:app:debugRuntimeClasspath`). It
        // IS exposed transitively to `:desktopApp`'s runtime classpath (where the rendering runs).
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // S4d-142: bundled SQLite driver so Room can build AppDatabase on JVM/Desktop
                // (BundledSQLiteDriver — JVM has no compatibility mode, an explicit driver is required).
                // DESKTOP target ONLY: it ships a host-native libsqliteJni and must NOT reach `:app`
                // (verified by the :app no-native-leak check; Android Room stays on framework SupportSQLite).
                implementation(libs.sqlite.bundled)
            }
        }
        // S4d-231: bundled SQLite driver for the iOS targets, so Room can build AppDatabase on iOS
        // (BundledSQLiteDriver — like Desktop, iOS/Native has no Room compatibility mode, an explicit
        // driver is required). iOS-TARGET-ONLY: it must NOT reach `:app`, which consumes `:shared`'s
        // android variant (Android stays on framework SupportSQLite, no driver). Verified by the
        // :app:assembleDebug no-sqlite-leak check. Attached to each iOS target's main source set
        // (created eagerly with the target, like `desktopMain`) rather than the hierarchy-template
        // intermediate `iosMain`, which is not yet resolvable via eager `by getting` at this point.
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
        // S4d-2: Skiko desktop runtime, TEST-SCOPE, so WatermarkCellComposerTest can RENDER the
        // commonMain Compose-graphics cell offscreen on the JVM host (ImageBitmap is Skia-backed on
        // desktop). `compose.ui` provides the API; this provides the backend. desktopTest already
        // inherits desktopMain's deps; kept explicit for the standalone test-render intent. Not in
        // any production/`:app` artifact; desktop deps do not leak to the Android consumer.
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "me.rosuh.easywatermark.shared"
    compileSdk = Apps.compileSdk

    defaultConfig {
        minSdk = Apps.minSdk
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// S4d-91: Room KSP must be registered for every target the proof DB compiles for. Per the KSP-MP
// contract, these go in the root dependencies block (one configuration per target). Desktop is the
// jvm("desktop") target, hence kspDesktop.
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
