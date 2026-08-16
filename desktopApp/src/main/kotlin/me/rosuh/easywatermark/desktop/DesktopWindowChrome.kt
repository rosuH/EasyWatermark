package me.rosuh.easywatermark.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Color
import java.awt.Window
import javax.swing.JRootPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import me.rosuh.easywatermark.ui.theme.DesignEditorBg

/** True when running on macOS (Darwin). */
internal fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * Top inset for product content when macOS fullWindowContent is active so controls sit
 * **below** the traffic-light strip (not under it). Background still paints edge-to-edge.
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
 * Tint the live window so system title-bar / edges match the product surface.
 *
 * [chrome] defaults to brand olive; pass the photo-seeded Editor chrome (ADR-0027 option B)
 * when Content editor theme is active so the mac title band matches the body.
 *
 * **macOS (option 2):** fullWindowContent + transparent title bar so Compose fill paints
 * under the traffic lights. Launch/Editor wrap **interactive** chrome with
 * [macFullWindowContentInsets]. About / Open Source paint edge-to-edge (olive + halo
 * under the lights) and inset only their content via [macTitleBarContentPadding].
 * Title text is hidden (product surface is the brand).
 * **Windows / Linux:** AWT backgrounds only.
 */
internal fun applyProductWindowChrome(
    window: Window,
    chrome: ComposeColor = DesignEditorBg,
) {
    val awt = chrome.toAwtColor()
    val apply = Runnable {
        try {
            window.background = awt
            val rootContainer = window as? RootPaneContainer
            rootContainer?.contentPane?.background = awt
            val root = rootContainer?.rootPane
            if (root != null) {
                root.background = awt
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

/** Compose sRGB → AWT (ignores color space; product colors are opaque sRGB). */
internal fun ComposeColor.toAwtColor(): Color {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return Color(r, g, b, a)
}

/**
 * Top padding for product UI when macOS full-window content is active; identity elsewhere.
 * Apply to the **content** layer only — keep the root fill edge-to-edge under the lights.
 */
internal fun Modifier.macFullWindowContentInsets(): Modifier =
    if (isMacOs()) padding(top = MacFullWindowContentTopInset) else this

/**
 * Content-only title-bar inset for full-bleed screens (About / Open Source).
 * Do **not** apply this to the screen root — that clips the About halo into a dark band.
 */
internal fun macTitleBarContentPadding(): PaddingValues =
    if (isMacOs()) PaddingValues(top = MacFullWindowContentTopInset) else PaddingValues()

private fun applyMacFullWindowContent(root: JRootPane) {
    if (!isMacOs()) return
    // Content draws under the title bar → product fill is the visible title-bar color.
    root.putClientProperty("apple.awt.fullWindowContent", true)
    root.putClientProperty("apple.awt.transparentTitleBar", true)
    // Hide system title string (would float over product fill with system typography).
    root.putClientProperty("apple.awt.windowTitleVisible", false)
}
