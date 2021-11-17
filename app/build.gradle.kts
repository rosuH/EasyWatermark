import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.kapt")
    id("dagger.hilt.android.plugin")
}

android {
    compileSdk = (Apps.compileSdk)
    buildToolsVersion = (Apps.buildTools)
    defaultConfig {
        applicationId = "me.rosuh.easywatermark"
        minSdk = (Apps.minSdk)
        targetSdk = (Apps.targetSdk)
        versionCode = 20501
        versionName = "2.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "$applicationId-v$versionName($versionCode)")
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

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }

    // change output apk name
    applicationVariants.all {
        outputs.all {
            (this as? BaseVariantOutputImpl)?.outputFileName =
                "$applicationId-v$versionName($versionCode).apk"
        }
    }

    packagingOptions {
        resources.excludes.add("DebugProbesKt.bin")
    }

    android.buildFeatures.viewBinding = true

    kotlinOptions {
        jvmTarget = "11"
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.dagger:hilt-android:2.38.1")
    kapt("com.google.dagger:hilt-compiler:2.38.1")
    implementation("com.github.bumptech.glide:glide:4.12.0")
    kapt("com.github.bumptech.glide:compiler:4.12.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.5.31")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("id.zelory:compressor:3.0.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("androidx.fragment:fragment-ktx:1.3.6")
    implementation("androidx.activity:activity-ktx:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.3.1")
    implementation("com.github.skydoves:colorpickerview:2.2.3")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.5.31")
    implementation("androidx.exifinterface:exifinterface:1.3.3")
    implementation("androidx.palette:palette-ktx:1.0.0")

    testImplementation("junit:junit:4.12")
    testImplementation("androidx.test:core:1.4.0")
    testImplementation("org.mockito:mockito-core:4.0.0")
    androidTestImplementation("org.mockito:mockito-android:4.0.0")
    androidTestImplementation("org.robolectric:robolectric:4.4")
    androidTestImplementation("androidx.test:rules:1.4.0")
    androidTestImplementation("androidx.test:runner:1.4.0")
    androidTestImplementation("org.hamcrest:hamcrest-library:2.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
}
