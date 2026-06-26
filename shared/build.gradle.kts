import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
    id(libs.plugins.android.library.get().pluginId)
    // C4.3: Kotlin-bundled Compose compiler + Compose Multiplatform. The latter delivers the
    // multiplatform (incl. iOS) androidx.compose graphics/text artifacts the future commonMain
    // renderer needs. Use ONLY compose.runtime/compose.ui — NO compose-resources / compose.components
    // (CMP-9547 stays out of scope). On Android these map to androidx.compose 1.11.2 (== :app's BOM).
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

/**
 * `:shared` — the KMP module (plan C4). Platform-neutral domain types + the watermark geometry core
 * in `commonMain`, compiling for Android + JVM(desktop) + iOS.
 *
 * C4.3 lineage unification: commonMain now also carries the multiplatform Compose graphics/text/
 * runtime types (via the Compose Multiplatform plugin) so the future commonMain renderer (S4d-2+)
 * can be written against them. This slice adds NO renderer logic — only a tiny compile probe
 * ([me.rosuh.easywatermark.render.ComposeTypeProbe]). On Android, CMP resolves to androidx.compose
 * 1.11.2, matching :app's bumped Compose BOM; :app substitutes any org.jetbrains.compose.* nodes to
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
