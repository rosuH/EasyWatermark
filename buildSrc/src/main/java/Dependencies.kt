object Apps {
    const val compileSdk = 29
    const val buildTools = "29.0.3"
    const val minSdk = 22
    const val targetSdk = 29
}

object BuildTypes {
    const val Release = "release"

    const val Debug = "debug"
}

object ProductFlavors {
    const val googlePlay = "GooglePlay"
    const val coolApk = "CoolApk"
    const val github = "Github"
    const val others = "others"
}

object Versions {
    const val palette: String = "1.0.0"

    const val exif: String = "1.3.2"

    const val gap = "4.0.1"

    const val kotlin = "1.4.30"

    const val coroutineCore = "1.4.2"

    const val coroutineAndroid = "1.4.2"

    const val appcompat = "1.2.0"

    const val lifeCycle = "2.3.0"

    const val material = "1.3.0"

    const val constraintLayout = "2.1.0-alpha2"

    const val materialAboutLibrary = "3.1.2"

    const val fragmentKtx = "1.3.0"

    const val activityKtx = "1.3.0-alpha02"

    const val coreKtx = "1.3.1"

    const val colorPickerView = "2.2.3"

    const val viewpager2 = "1.0.0"

    const val recyclerView = "1.0.0"

    const val compressor = "3.0.0"

    const val gifDrawable = "1.2.19"

    const val junit = "4.12"

    const val androidXTest = "1.3.0"

    const val mock = "+"

    const val robolectric = "4.4"

    const val testRule = "1.3.0"

    const val hamcrest = "2.2"

    const val espresso = "3.1.0"

    const val uiautomator = "2.2.0"

    const val testJunitExt = "1.0.0"
}

object Libs {
    const val kotlin = "org.jetbrains.kotlin:kotlin-stdlib:${Versions.kotlin}"

    const val coroutineAndroid =
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutineAndroid}"

    const val coroutineCore =
        "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutineCore}"

    const val gap = "com.android.tools.build:gradle:${Versions.gap}"

    const val compressor = "id.zelory:compressor:${Versions.compressor}"

    const val materialAboutLibrary =
        "com.github.daniel-stoneuk:material-about-library:${Versions.materialAboutLibrary}"

    const val fragmentKtx = "androidx.fragment:fragment-ktx:${Versions.fragmentKtx}"

    const val fragment = "androidx.fragment:fragment:${Versions.fragmentKtx}"

    const val activityKtx = "androidx.activity:activity-ktx:${Versions.activityKtx}"

    const val lifecycleViewModel =
        "androidx.lifecycle:lifecycle-viewmodel-ktx:${Versions.lifeCycle}"

    const val lifecycleLiveData = "androidx.lifecycle:lifecycle-livedata-ktx:${Versions.lifeCycle}"

    const val material = "com.google.android.material:material:${Versions.material}"

    const val colorPickerView = "com.github.skydoves:colorpickerview:${Versions.colorPickerView}"

    const val viewpager2 = "androidx.viewpager2:viewpager2:${Versions.viewpager2}"

    const val recycleView = "androidx.recyclerview:recyclerview:${Versions.recyclerView}"

    const val constraintLayout =
        "androidx.constraintlayout:constraintlayout:${Versions.constraintLayout}"

    const val coreKtx = "org.jetbrains.kotlin:kotlin-stdlib:${Versions.coreKtx}"

    const val appcompat = "androidx.appcompat:appcompat:${Versions.appcompat}"

    const val exif = "androidx.exifinterface:exifinterface:${Versions.exif}"

    const val palette = "androidx.palette:palette:${Versions.palette}"
}

object TestLibs {
    const val junit = "junit:junit:${Versions.junit}"
    const val androidXTest = "androidx.test:core:${Versions.androidXTest}"
    const val testRunner = "androidx.test:runner:${Versions.testRule}"
    const val testRules = "androidx.test:rules:${Versions.testRule}"
    const val junitExt = "androidx.test.ext:junit:${Versions.testJunitExt}"

    const val mock = "org.mockito:mockito-core:${Versions.mock}"
    const val mockAndroid = "org.mockito:mockito-android:${Versions.mock}"
    const val mockInline = "org.mockito:mockito-inline:${Versions.mock}"

    const val robolectric = "org.robolectric:robolectric:${Versions.robolectric}"
    const val hamcrest = "org.hamcrest:hamcrest-library:${Versions.hamcrest}"
    const val espresso = "androidx.test.espresso:espresso-core:${Versions.espresso}"
    const val uiautomator = "androidx.test.uiautomator:uiautomator:${Versions.uiautomator}"
}