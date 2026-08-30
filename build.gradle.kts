buildscript {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }

    dependencies {
        classpath(libs.agp)
        classpath(libs.kotlin.gradlePlugin)
//        classpath(libs.hilt.plugin)
    }
}


plugins {
//    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    // C4.3: Compose Multiplatform plugin on the root classpath; applied only by :shared.
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    // S4d-360: official Android-KMP library plugin for :shared (AGP 9).
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    // Compose stability analyzer 0.13.0 (Kotlin 2.4.10, Isolated Projects) — applied by :app for DEBUG TraceRecomposition.
    alias(libs.plugins.stability.analyzer) apply false
//    alias(libs.plugins.spotless) apply false
}

//allprojects {
//    plugins.apply(rootProject.libs.plugins.spotless.get().pluginId)
//    extensions.configure<SpotlessExtension> {
//        kotlin {
//            target("src/**/*.kt")
//            ktlint(rootProject.libs.ktlint.get().version)
//        }
//        kotlinGradle {
//            ktlint(rootProject.libs.ktlint.get().version)
//        }
//    }
//}

/**
 * E3 ownership fitness (issue 35 / issue 12 P4).
 * Fail-closed static + focused Session tests via scripts/verify-ownership-fitness.sh.
 */
tasks.register<Exec>("verifyOwnershipFitness") {
    group = "verification"
    description = "E3 ownership fitness gates (route/selection static + Session unit tests)"
    workingDir = rootDir
    commandLine("bash", "scripts/verify-ownership-fitness.sh")
}