package me.rosuh.easywatermark.platform

import me.rosuh.easywatermark.ui.theme.MotionPolicy

/**
 * Desktop: no portable OS reduce-motion API in this product → [MotionPolicy.Full].
 * Hosts may still [me.rosuh.easywatermark.ui.theme.ProvideMotionPolicy] an override.
 */
actual fun platformMotionPolicy(): MotionPolicy = MotionPolicy.Full
