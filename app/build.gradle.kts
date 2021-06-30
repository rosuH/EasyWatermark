import ProductFlavors.coolApk
import ProductFlavors.github
import ProductFlavors.googlePlay
import ProductFlavors.others
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("com.github.ben-manes.versions")
}

android {
    compileSdkVersion(Apps.compileSdk)
    buildToolsVersion(Apps.buildTools)
    ndkVersion = "21.3.6528147"
    defaultConfig {
        applicationId = "me.rosuh.easywatermark"
        minSdkVersion(Apps.minSdk)
        targetSdkVersion(Apps.targetSdk)
        versionCode = 20100
        versionName = "2.1.0"
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
                "coroutines.pro", "proguard-rules.pro"
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

    packagingOptions {
        exclude("DebugProbesKt.bin")
    }

    android.buildFeatures.viewBinding = true

}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${Versions.kotlin}")
    implementation("androidx.appcompat:appcompat:1.4.0-alpha02")
    implementation("id.zelory:compressor:3.0.1")
    implementation("com.github.daniel-stoneuk:material-about-library:3.1.2")
    implementation("com.google.android.material:material:1.3.0")
    implementation("androidx.fragment:fragment-ktx:1.4.0-alpha04")
    implementation("androidx.activity:activity-ktx:1.3.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.3.1")
    implementation("com.github.skydoves:colorpickerview:2.2.3")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.0-beta02")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.5.20")
    implementation("androidx.exifinterface:exifinterface:1.3.2")
    implementation("androidx.palette:palette-ktx:1.0.0")

    testImplementation("junit:junit:4.12")
    testImplementation("androidx.test:core:1.3.0")
    testImplementation("org.mockito:mockito-core:+")
    androidTestImplementation("org.mockito:mockito-android:+")
    androidTestImplementation("org.robolectric:robolectric:4.4")
    androidTestImplementation("androidx.test:rules:1.3.0")
    androidTestImplementation("androidx.test:runner:1.3.0")
    androidTestImplementation("org.hamcrest:hamcrest-library:2.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.0.0")
}