package me.rosuh.easywatermark.ui

/**
 * Light selection tick for filmstrip center-snap (platform-backed so iOS actually vibrates).
 */
expect object PlatformHaptics {
    fun selectionTick()
}
