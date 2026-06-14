import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId)
    id(libs.plugins.android.library.get().pluginId)
}

/**
 * `:shared` — the first Compose Multiplatform / KMP module (plan C4, brought online early as a
 * minimal, platform-neutral foundation). Holds pure-Kotlin domain types in `commonMain` that
 * compile for Android AND JVM (desktop), consumed by `:app`. Compose / Android-resource code is
 * deliberately NOT here yet (avoids CMP-9547); iOS targets are added in C5.
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
