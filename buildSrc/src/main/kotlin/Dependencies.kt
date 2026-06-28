object Apps {
    const val compileSdk = 36
    const val buildTools = "36.0.0"
    const val minSdk = 23
    const val targetSdk = 36
    // S4d-175: single source of truth for the product version, consumed by :app (AGP versionCode/versionName)
    // and :desktopApp (Compose Desktop nativeDistributions.packageVersion).
    const val versionCode = 21000
    const val versionName = "2.10.0"
}