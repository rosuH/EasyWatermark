package me.rosuh.easywatermark

/**
 * Single product version surface for all platforms (matches [Apps.versionName] / Android
 * `BuildConfig.VERSION_NAME`). Hosts must not hard-code platform labels like `"iOS"`.
 */
object ProductVersion {
    const val NAME: String = "3.0.0"
    const val CODE: Int = 30000
}
