package me.rosuh.easywatermark.ui

/**
 * Android selection tick is driven from Compose [LocalHapticFeedback] in [EditorPhotoStrip].
 * This actual is a no-op backup (no View reference required).
 */
internal actual fun platformSelectionTick() {
    // No-op: Compose LocalHapticFeedback handles Android.
}
