import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    id(libs.plugins.android.test.get().pluginId)
    // S4d-360: AGP 9 built-in Kotlin — do not apply org.jetbrains.kotlin.android.
}

// [START macrobenchmark_setup_android]
android {
    // [START_EXCLUDE]
    // S4d-363: align with project Apps.compileSdk (37); minSdk/targetSdk/managedDevices unchanged.
    compileSdk = Apps.compileSdk
    namespace = "me.rosuh.macrobenchmark"

    defaultConfig {
        minSdk = Apps.minSdk
        targetSdk = Apps.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // H1: allow local/managed-emulator smoke for the measurement lane.
        // Physical-device numbers remain the accuracy gold standard (document residual).
        // Does NOT invent H3 latency SLOs — only unblocks FrameTimingMetric collection.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    testOptions {
        managedDevices {
            allDevices {
                create<ManagedVirtualDevice>("pixel6Api31") {
                    device = "Pixel 6"
                    apiLevel = 31
                    systemImageSource = "aosp"
                }
            }
        }
    }
    // [END_EXCLUDE]
    // Note that your module name may have different name
    targetProjectPath = ":app"
    // Enable the benchmark to run separately from the app process
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // H1: test APK (self-instrumenting) may be debuggable; the *app under test*
        // is :app benchmark/release (non-debuggable, minified). Do not flip this to false
        // without re-validating Macrobenchmark self-instrumenting install.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            // [START_EXCLUDE silent]
            // Selects release buildType if the benchmark buildType not available in other modules.
            matchingFallbacks.add("release")
            // [END_EXCLUDE]
        }
    }
    kotlin {
        jvmToolchain(17)
    }
}
// [END macrobenchmark_setup_android]

// [START macrobenchmark_setup_variant]
androidComponents {
    beforeVariants(selector().all()) {
        // enable only the benchmark buildType, since we only want to measure close to release performance
        it.enable = it.buildType == "benchmark"
    }
}
// [END macrobenchmark_setup_variant]

dependencies {
    implementation(project(":baseBenchmarks"))
    implementation(libs.benchmark)
    implementation(libs.test.ext.junit)
    implementation(libs.test.espresso.core)
    implementation(libs.test.uiautomator)
    implementation(libs.kotlin.stdlib)
    implementation(libs.core.ktx)
}
