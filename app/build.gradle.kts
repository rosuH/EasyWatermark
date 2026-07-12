import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

plugins {
    id(libs.plugins.android.application.get().pluginId)
    // S4d-360: AGP 9 built-in Kotlin — do not apply org.jetbrains.kotlin.android.
    // Keep serialization + compose-compiler + ksp (still separate compiler plugins).
    id(libs.plugins.kotlin.serialization.get().pluginId)
    id(libs.plugins.ksp.get().pluginId)
//    id(libs.plugins.hilt.plugin.get().pluginId)
    alias(libs.plugins.compose.compiler)
//    id(libs.plugins.spotless.get().pluginId)
}

// C4.3 Compose lineage unification: :shared (Compose Multiplatform) transitively brings
// `org.jetbrains.compose.*` coordinates onto :app's Android classpath. On Android these are the same
// classes as `androidx.compose.*` (CMP delegates to Jetpack Compose), so we substitute them to the
// AndroidX coordinates. S4d-236: use the original dependency's version (not a hard-coded one) so
// artifacts on different version lines (material3 at 1.4.0, annotation-internal, etc.) resolve
// correctly; the Compose BOM (2026.05.01 -> 1.11.2) aligns the core compose.* artifacts.
// Build-config only; no source/renderer/UI behavior change.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        all {
            val selector = requested
            if (selector is ModuleComponentSelector && selector.group.startsWith("org.jetbrains.compose.")) {
                val androidxGroup = selector.group.replaceFirst("org.jetbrains.compose", "androidx.compose")
                useTarget(
                    "$androidxGroup:${selector.module}:${selector.version}",
                    "C4.3: unify Compose lineage to AndroidX on Android",
                )
            }
        }
    }
}

android {
    compileSdk = (Apps.compileSdk)
    buildToolsVersion = (Apps.buildTools)
    defaultConfig {
        applicationId = "me.rosuh.easywatermark"
        minSdk = (Apps.minSdk)
        targetSdk = (Apps.targetSdk)
        versionCode = (Apps.versionCode)
        versionName = (Apps.versionName)
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

    packaging {
        resources.excludes.add("DebugProbesKt.bin")
    }

    namespace = "me.rosuh.easywatermark"

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // AGP 9 built-in Kotlin: jvmToolchain stays on android.kotlin.
    kotlin {
        jvmToolchain(17)
    }
}

/**
 * S4d-360/S4d-362: public Android Components artifacts API for custom APK naming.
 * Replaces removed `applicationVariants` + internal `ApkVariantOutputImpl` (no reflection).
 * Listens to [SingleArtifact.APK] (directory) and copies the packaged APK under the release name.
 *
 * Deterministic output:
 *   app/build/outputs/apk/<variant>/renamed/EasyWatermark-<versionName>-<versionCode>.apk
 * assemble<Variant> is finalizedBy the matching copy task so the rename always runs with assemble.
 */
abstract class CopyRenamedApkTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkFolder: DirectoryProperty

    @get:OutputDirectory
    abstract val outputFolder: DirectoryProperty

    @get:Input
    abstract val apkFileName: Property<String>

    @TaskAction
    fun copy() {
        val apk = apkFolder.get().asFile
            .listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?: error("No APK found in ${apkFolder.get().asFile}")
        val outDir = outputFolder.get().asFile.apply { mkdirs() }
        apk.copyTo(File(outDir, apkFileName.get()), overwrite = true)
    }
}

val apkBaseName = "EasyWatermark-${Apps.versionName}-${Apps.versionCode}.apk"
extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<CopyRenamedApkTask>("copyRenamed${capitalized}Apk") {
            // Deterministic renamed directory consumed by .github/workflows/release.yml
            outputFolder.set(layout.buildDirectory.dir("outputs/apk/${variant.name}/renamed"))
            apkFileName.set(apkBaseName)
        }
        // Public API: wire task input to packaged APK directory (SingleArtifact.APK is a Directory).
        variant.artifacts.use(copyTask)
            .wiredWith(CopyRenamedApkTask::apkFolder)
            .toListenTo(SingleArtifact.APK)

        // S4d-362: toListenTo alone does not schedule the listener; attach to assemble so the
        // renamed APK is always produced for assembleRelease / assembleDebug / etc.
        tasks.matching { it.name == "assemble$capitalized" }.configureEach {
            finalizedBy(copyTask)
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
//    implementation(libs.hilt.android)
//    ksp(libs.hilt.compiler)
//    androidTestImplementation(libs.hilt.testing)
//    kspAndroidTest(libs.hilt.compiler)

    implementation(libs.compressor)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.coroutine.android)
    implementation(libs.kotlin.coroutine.core)

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.exifinterface)
    implementation(libs.profileinstaller)

    testImplementation(libs.test.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.test.ext.junit)

    // or only import the main APIs for the underlying toolkit systems,
    // such as input and measurement/layout
//    val composeBom = platform("androidx.compose:compose-bom:2023.10.00")
//    implementation(composeBom)
//    androidTestImplementation(composeBom)
    implementation(enforcedPlatform(libs.androidx.compose.bom))
    androidTestImplementation(enforcedPlatform(libs.androidx.compose.bom))
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
    // S4d-38: Coil 3 Compose only (io.coil-kt.coil3:coil-compose). ImageRequest comes transitively from
    // coil-core; no coil base/View artifact, no coil-svg (SvgDecoder unused), no coil-network (local Uris).
    implementation(libs.coil.kt.compose)

//    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation(libs.androidx.lifecycle.runtime.compose)

//    implementation("androidx.navigation:navigation-compose:2.7.4")
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":shared"))

//    implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")
    implementation(libs.androidx.constraintlayout.compose)
//    implementation(libs.androidx.motionlayoout.compose)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
}
