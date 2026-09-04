package me.rosuh.easywatermark.ui

/**
 * Light selection tick for filmstrip center-snap.
 *
 * Concrete object (not `expect object`) so Desktop's JVM classloader always
 * finds `me.rosuh.easywatermark.ui.PlatformHaptics` — expect classes are Beta
 * and were missing at filmstrip click (`NoClassDefFoundError`).
 */
object PlatformHaptics {
    fun selectionTick() = platformSelectionTick()
}

internal expect fun platformSelectionTick()
