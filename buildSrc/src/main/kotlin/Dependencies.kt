object Apps {
    // S4d-362: AAR metadata from absolute Compose stack requires compileSdk ≥ 37
    // (first failure: animation-core-android:1.12.0-beta02 vs android-36).
    // targetSdk stays 36 — compileSdk may advance independently of runtime target.
    const val compileSdk = 37
    // S4d-371: absolute-latest build-tools (incl. prereleases) — 37.0.0 installed via android CLI.
    const val buildTools = "37.0.0"
    const val minSdk = 23
    const val targetSdk = 36
    // S4d-175: single source of truth for the product version, consumed by :app (AGP versionCode/versionName)
    // and :desktopApp (Compose Desktop nativeDistributions.packageVersion).
    const val versionCode = 21000
    const val versionName = "2.10.0"
}