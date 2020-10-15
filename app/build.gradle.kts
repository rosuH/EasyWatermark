import ProductFlavors.coolApk
import ProductFlavors.github
import ProductFlavors.googlePlay
import ProductFlavors.others
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-android-extensions")
}

android {
    compileSdkVersion(Apps.compileSdk)
    buildToolsVersion(Apps.buildTools)
    ndkVersion = "21.3.6528147"
    defaultConfig {
        applicationId = "me.rosuh.easywatermark"
        minSdkVersion(Apps.minSdk)
        targetSdkVersion(Apps.targetSdk)
        versionCode = Apps.versionCode
        versionName = Apps.versionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {

        getByName(BuildTypes.Debug) {
            isMinifyEnabled = false
            applicationIdSuffix = ".${BuildTypes.Debug}"
            versionNameSuffix = ".${BuildTypes.Debug}"
            isDebuggable = true
        }

        getByName(BuildTypes.Release) {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions("version")

    productFlavors {
        create(googlePlay)
        create(github)
        create(coolApk)
        create(others) {
            isDefault = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "ewm-v${variant.versionName}.apk"
            }
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(Libs.kotlin)
    implementation(Libs.appcompat)
    implementation(Libs.compressor)
    implementation(Libs.materialAboutLibrary)
    implementation(Libs.material)
    implementation(Libs.fragmentKtx)
    implementation(Libs.lifecycleLiveData)
    implementation(Libs.lifecycleViewModel)
    implementation(Libs.colorPickerView)
    implementation(Libs.viewpager2)
    implementation(Libs.recycleView)
    implementation(Libs.constraintLayout)
    implementation(Libs.coreKtx)
    implementation(Libs.exif)
    testImplementation(TestLibs.junit)
    testImplementation(TestLibs.androidXTest)
    testImplementation(TestLibs.mock)
    androidTestImplementation(TestLibs.mockAndroid)
    androidTestImplementation(TestLibs.robolectric)
    androidTestImplementation(TestLibs.testRules)
    androidTestImplementation(TestLibs.testRunner)
    androidTestImplementation(TestLibs.hamcrest)
    androidTestImplementation(TestLibs.espresso)
    androidTestImplementation(TestLibs.uiautomator)
    androidTestImplementation(TestLibs.junitExt)
}