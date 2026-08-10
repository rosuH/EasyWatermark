package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Color
import java.awt.Window
import javax.swing.JRootPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Product olive for AWT/Swing window chrome — matches commonMain [DesignEditorBg] `0xFF262611`.
 * Kept as a plain AWT color so chrome install does not depend on Compose runtime.
 */
private val ProductWindowOlive = Color(0x26, 0x26, 0x11)

/** True when running on macOS (Darwin). */
internal fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * Top inset for product content when macOS fullWindowContent is active so controls sit
 * **below** the traffic-light strip (not under it). Olive background still paints edge-to-edge.
 *
 * ~52.dp matches a standard title-bar band on Retina-scale Compose Desktop windows; slightly
 * taller than the minimal 28pt bar so IconButtons clear the lights comfortably.
 */
internal val MacFullWindowContentTopInset: Dp = 52.dp

/**
 * Call **before** the first AWT/Compose window is created so macOS picks a dark app appearance
 * (title bar / menu bar) instead of the default light chrome over the olive product surface.
 */
internal fun installDesktopProductAppearanceEarly() {
    if (!isMacOs()) return
    // Only set when the host has not already chosen an appearance (allows -D override).
    if (System.getProperty("apple.awt.application.appearance") != null) return
    // Always-dark chrome: product UI is forced-dark olive; a light title bar reads as a white stripe.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

/**
 * Tint the live window so system title-bar / edges match the product olive surface.
 *
 * **macOS (option 2):** fullWindowContent + transparent title bar so Compose olive paints
 * under the traffic lights. Pair with [macFullWindowContentInsets] so interactive chrome is
 * not under the lights. Title text is hidden (product surface is the brand).
 * **Windows / Linux:** olive AWT backgrounds only.
 */
internal fun applyProductWindowChrome(window: Window) {
    val apply = Runnable {
        try {
            window.background = ProductWindowOlive
            val rootContainer = window as? RootPaneContainer
            rootContainer?.contentPane?.background = ProductWindowOlive
            val root = rootContainer?.rootPane
            if (root != null) {
                root.background = ProductWindowOlive
                applyMacFullWindowContent(root)
            }
        } catch (_: Throwable) {
            // Best-effort only.
        }
    }
    if (SwingUtilities.isEventDispatchThread()) {
        apply.run()
    } else {
        SwingUtilities.invokeLater(apply)
    }
}

/**
 * Top padding for product UI when macOS full-window content is active; identity elsewhere.
 * Apply to the **content** layer only — keep the olive root fill edge-to-edge under the lights.
 */
internal fun Modifier.macFullWindowContentInsets(): Modifier =
    if (isMacOs()) padding(top = MacFullWindowContentTopInset) else this

private fun applyMacFullWindowContent(root: JRootPane) {
    if (!isMacOs()) return
    // Content draws under the title bar → product olive is the visible title-bar color.
    root.putClientProperty("apple.awt.fullWindowContent", true)
    root.putClientProperty("apple.awt.transparentTitleBar", true)
    // Hide system title string (would float over olive with system typography).
    root.putClientProperty("apple.awt.windowTitleVisible", false)
}
