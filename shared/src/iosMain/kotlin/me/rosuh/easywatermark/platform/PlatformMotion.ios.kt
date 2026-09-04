package me.rosuh.easywatermark.platform

import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.motionPolicyFromReduceMotionFlag
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * iOS: [UIAccessibilityIsReduceMotionEnabled] → [MotionPolicy.Reduced], else Full.
 * (No separate Off scale on iOS — Off only if host forces it.)
 */
actual fun platformMotionPolicy(): MotionPolicy =
    motionPolicyFromReduceMotionFlag(UIAccessibilityIsReduceMotionEnabled())
