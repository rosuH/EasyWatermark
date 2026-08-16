package me.rosuh.easywatermark.ui

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

internal actual fun platformSelectionTick() {
    val gen = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    gen.prepare()
    gen.impactOccurred()
}
