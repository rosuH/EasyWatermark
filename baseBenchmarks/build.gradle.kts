plugins {
    id(libs.plugins.android.library.get().pluginId)
    // S4d-360: AGP 9 built-in Kotlin — do not apply org.jetbrains.kotlin.android.
    id(libs.plugins.ksp.get().pluginId)
}

android {
    namespace = "me.rosuh.benchmark.macro.base"
    // S4d-363: align with project Apps.compileSdk (37); minSdk/testOptions.targetSdk unchanged.
    compileSdk = Apps.compileSdk

    defaultConfig {
        minSdk = Apps.minSdk

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        targetSdk = Apps.targetSdk
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(libs.core.ktx)
}
