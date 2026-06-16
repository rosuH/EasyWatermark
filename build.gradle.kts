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
