plugins {
    id(libs.plugins.android.library.get().pluginId)
    // S4d-360: AGP 9 built-in Kotlin — do not apply org.jetbrains.kotlin.android.
    id(libs.plugins.ksp.get().pluginId)
}

android {
    namespace = "me.rosuh.cmonet"
    // S4d-362: compile against Apps.compileSdk (37), not targetSdk (36) — AAR metadata
    // requires core-ktx 1.19.0 consumers to use compileSdk ≥ 37.
    compileSdk = Apps.compileSdk

    defaultConfig {
        minSdk = Apps.minSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)
    testImplementation(libs.test.junit)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.espresso.core)
}