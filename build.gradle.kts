// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {

    repositories {
        google()
        jcenter()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:4.1.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.kotlin}")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        google()
        jcenter()
        maven { url = uri("https://jitpack.io") }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}