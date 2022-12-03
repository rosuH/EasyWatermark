include(":app")
include(":cmonet")
include(":baseBenchmarks")
include(":macrobenchmark")


dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // plugins
            val kotlinVersion = "1.7.20"
            library("dagger-hilt-plugin", "com.google.dagger:hilt-android-gradle-plugin:2.42")
            library("tools-gradle", "com.android.tools.build:gradle:7.3.1")
            library("kotlin-plugin", "org.jetbrains.kotlin:kotlin-gradle-plugin:${kotlinVersion}")
            library("ktlint-gradle", "org.jlleitschuh.gradle:ktlint-gradle:10.1.0")

            // kotlin libs
            library("kotlin-stdlib", "org.jetbrains.kotlin:kotlin-stdlib:${kotlinVersion}")
            library("kotlin-coroutine-android", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
            library("kotlin-coroutine-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

            // android platforms libs
            library("fragment-ktx", "androidx.fragment:fragment-ktx:1.5.4")
            library("activity-ktx", "androidx.activity:activity-ktx:1.6.1")
            library("lifecycle-runtime-ktx", "androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
            library("lifecycle-livedata-ktx", "androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
            library("lifecycle-viewModel-ktx", "androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
            library("core-ktx", "androidx.core:core-ktx:1.7.20")
            library("appcompat", "androidx.appcompat:appcompat:1.5.0")
            library("material", "com.google.android.material:material:1.8.0-alpha01")
            val roomVersion = "2.4.3"
            library("room-runtime", "androidx.room:room-runtime:${roomVersion}")
            library("room-ktx", "androidx.room:room-ktx:${roomVersion}")
            library("room-compiler", "androidx.room:room-compiler:$roomVersion")
            library("datastore-preference", "androidx.datastore:datastore-preferences:1.0.0")
            library("asyncLayoutInflater", "androidx.asynclayoutinflater:asynclayoutinflater:1.0.0")
            library("viewpager2", "androidx.viewpager2:viewpager2:1.0.0")
            library("recyclerview", "androidx.recyclerview:recyclerview:1.2.1")
            library("constraintLayout", "androidx.constraintlayout:constraintlayout:2.1.4")
            library("exifInterface", "androidx.exifinterface:exifinterface:1.3.5")
            library("palette-ktx", "androidx.palette:palette-ktx:1.0.0")

            // third party libs
            val daggerVersion = "2.43.2"
            library("dagger-hilt-android", "com.google.dagger:hilt-android:${daggerVersion}")
            library("dagger-hilt-compiler", "com.google.dagger:hilt-compiler:${daggerVersion}")

            val glideVersion = "4.14.2"
            library("glide-glide", "com.github.bumptech.glide:glide:${glideVersion}")
            library("glide-compiler", "com.github.bumptech.glide:compiler:${glideVersion}")

            library("compressor", "id.zelory:compressor:3.0.1")
            library("colorpicker", "com.github.skydoves:colorpickerview:2.2.3")


            // benchmark && test libs
            library("benchmark", "androidx.benchmark:benchmark-macro-junit4:1.1.1")
            library("profieinstaller", "androidx.profileinstaller:profileinstaller:1.2.0")

            val testVersion = "1.4.0"
            library("test-core", "androidx.test:core:${testVersion}")
            library("test-rules", "androidx.test:rules:${testVersion}")
            library("test-runner", "androidx.test:runner:${testVersion}")

            library("test-junit", "junit:junit:4.13.2")
            library("test-espresso-core", "androidx.test.espresso:espresso-core:3.4.0")
            library("test-uiautomator", "androidx.test.uiautomator:uiautomator:2.2.0")
            library("test-ext-junit", "androidx.test.ext:junit:1.1.4")
            library("mockito-core", "org.mockito:mockito-core:4.0.0")
            library("mockito-android", "org.mockito:mockito-android:4.0.0")
            library("robolectric", "org.robolectric:robolectric:4.4")
            library("hamcrest-library", "org.hamcrest:hamcrest-library:2.2")
        }
    }
}
