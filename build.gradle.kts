buildscript {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }

    dependencies {
        classpath(libs.agp)
        classpath(libs.kotlin.gradlePlugin)
        classpath(libs.hilt.plugin)
    }
}


tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

//apply(plugin = "org.jlleitschuh.gradle.ktlint")
//
//subprojects {
//    apply(plugin = "org.jlleitschuh.gradle.ktlint") // Version should be inherited from parent
//
//    repositories {
//        // Required to download KtLint
//        mavenCentral()
//    }
//
//    // Optionally configure plugin
//    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
//        debug.set(true)
//    }
//}

plugins {
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
