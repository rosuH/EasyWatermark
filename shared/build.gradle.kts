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
    iosArm64()
    iosSimulatorArm64()

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
