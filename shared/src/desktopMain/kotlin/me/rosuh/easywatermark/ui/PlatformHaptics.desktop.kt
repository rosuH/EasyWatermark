package me.rosuh.easywatermark.ui

actual object PlatformHaptics {
    actual fun selectionTick() {
        // No haptic hardware on desktop.
    }
}
