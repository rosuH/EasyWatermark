package me.rosuh.easywatermark.ui

/**
 * Android selection tick is driven from Compose [LocalHapticFeedback] in [EditorPhotoStrip].
 * This actual is a no-op backup (no View reference required).
 */
actual object PlatformHaptics {
    actual fun selectionTick() {
        // No-op: Compose LocalHapticFeedback handles Android.
    }
}
