import com.android.build.gradle.internal.api.ApkVariantOutputImpl

plugins {
    id(libs.plugins.android.application.get().pluginId)
    id(libs.plugins.kotlin.android.get().pluginId)
    id(libs.plugins.kotlin.parcelize.get().pluginId)
    id(libs.plugins.ksp.get().pluginId)
    id(libs.plugins.hilt.plugin.get().pluginId)
    id(libs.plugins.spotless.get().pluginId)
}

/**
 * read version from gradle.properties
 */
val majorVersion by properties
val minorVersion by properties
val patchVersion by properties

fun getVersionCode(): Int {
    return (majorVersion as String).toInt() * 10000 + (minorVersion as String).toInt() * 100 + (patchVersion as String).toInt()
}

fun getVersionName(): String {
    return "$majorVersion.$minorVersion.$patchVersion"
}

android {
    compileSdk = (Apps.compileSdk)
    buildToolsVersion = (Apps.buildTools)
    defaultConfig {
        applicationId = "me.rosuh.easywatermark"
        minSdk = (Apps.minSdk)
        targetSdk = (Apps.targetSdk)
        versionCode = getVersionCode()
        versionName = getVersionName()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        val debug by getting {
            applicationIdSuffix = ".debug"
        }

        val release by getting {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "coroutines.pro", "proguard-rules.pro"
            )
        }

        create("benchmark") {
            initWith(release)
            signingConfig = signingConfigs.getByName("debug")
            // [START_EXCLUDE silent]
            // Selects release buildType if the benchmark buildType not available in other modules.
            matchingFallbacks.add("release")
            // [END_EXCLUDE]
            proguardFiles("benchmark-rules.pro")
        }
    }

    packagingOptions {
        resources.excludes.add("DebugProbesKt.bin")
    }

    android.buildFeatures.viewBinding = true
    
    namespace = "me.rosuh.easywatermark"

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.androidxComposeCompiler.get()
    }

    kotlin {
        jvmToolchain(17)
    }

    applicationVariants.configureEach {
        outputs.configureEach {
            (this as? ApkVariantOutputImpl)?.outputFileName =
                "EasyWatermark-$versionName-$versionCode.apk"
        }
    }
}


dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(mapOf("path" to ":cmonet")))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.core.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preference)

    // di
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)

    implementation(libs.asynclayout.inflater)

    implementation(libs.glide)
    ksp(libs.glide.compiler)

    implementation(libs.compressor)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutine.android)
    implementation(libs.kotlin.coroutine.core)

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.fragment.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.viewpager2)
    implementation(libs.recyclerview)
    implementation(libs.constraintlayout)
    implementation(libs.exifinterface)
    implementation(libs.palette.ktx)
    implementation(libs.profileinstaller)

    implementation(libs.colorpicker)


    testImplementation(libs.test.junit)
    testImplementation(libs.test.rules)
    testImplementation(libs.test.runner)
    androidTestImplementation(libs.mockito.core)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.robolectric)
    androidTestImplementation(libs.hamcrest.library)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.uiautomator)
    androidTestImplementation(libs.test.ext.junit)

    // or only import the main APIs for the underlying toolkit systems,
    // such as input and measurement/layout
//    val composeBom = platform("androidx.compose:compose-bom:2023.10.00")
//    implementation(composeBom)
//    androidTestImplementation(composeBom)
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    //    implementation("androidx.compose.material3:material3:1.2.0-alpha09")
//    implementation("androidx.compose.material3:material3-window-size-class:1.1.2")
//    implementation(libs.material)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSizeClass)
//    implementation("androidx.compose.ui:ui")
    implementation(libs.androidx.compose.ui.ui)

//    implementation("androidx.compose.ui:ui-tooling-preview")
//    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.tooling)

    // Optional - Integration with activities
//    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(libs.androidx.activity.compose)
    // Optional - Integration with ViewModels
//    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation(libs.androidx.compose.lifecycle.viewmodel)

    // When using a MDC theme
//    implementation("com.google.android.material:compose-theme-adapter:1.2.1")

//    implementation("com.google.accompanist:accompanist-permissions:0.33.2-alpha")
    implementation(libs.accompanist.permissions)
//    implementation("io.coil-kt:coil-compose:2.3.0")
    implementation(libs.coil.kt)
    implementation(libs.coil.kt.compose)
    implementation(libs.coil.kt.svg)

//    implementation("androidx.compose.runtime:runtime-livedata:1.5.3")
    implementation(libs.androidx.compose.runtime.livedata)

//    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation(libs.androidx.lifecycle.runtime.compose)

//    implementation("androidx.navigation:navigation-compose:2.7.4")
    implementation(libs.androidx.navigation.compose)

//    implementation("com.google.accompanist:accompanist-navigation-animation:0.31.1-alpha")
    implementation(libs.accompanist.navigation.animation)

//    implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.motionlayoout.compose)

}
