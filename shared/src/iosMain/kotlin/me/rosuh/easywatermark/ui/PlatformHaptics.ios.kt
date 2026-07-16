package me.rosuh.easywatermark.ui

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

actual object PlatformHaptics {
    actual fun selectionTick() {
        val gen = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
        gen.prepare()
        gen.impactOccurred()
    }
}
