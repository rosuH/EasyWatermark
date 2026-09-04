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
 * under the traffic lights. Those JRootPane client properties are ignored after the
 * window is displayable — call this from [androidx.compose.ui.awt.SwingWindow] `init`
 * (before first show), then again when [chrome] changes.
 *
 * Editor wraps **interactive** chrome with [macFullWindowContentInsets]. Launch / About /
 * Open Source paint edge-to-edge (olive, About halo under the lights) and inset only
 * content that would sit under the traffic lights via [macTitleBarContentPadding].
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
            if (window.isDisplayable) {
                applyMacStyleBitsViaPeer(window)
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
 * After first show: re-apply chrome and nudge the frame so the Skia layer
 * picks up fullWindowContent bounds (CMP-235: properties after display + resize).
 */
internal fun realizeMacTitleBarAfterShown(
    window: Window,
    chrome: ComposeColor = DesignEditorBg,
) {
    applyProductWindowChrome(window, chrome)
    if (!isMacOs()) return
    val w = window.width
    val h = window.height
    if (w > 1 && h > 1) {
        window.setSize(w, h + 1)
        applyProductWindowChrome(window, chrome)
        window.setSize(w, h)
        applyProductWindowChrome(window, chrome)
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
    // SwingWindow init (before displayable) + again after first show.
    root.putClientProperty("apple.awt.fullWindowContent", true)
    root.putClientProperty("apple.awt.transparentTitleBar", true)
    // Hide system title string (would float over product fill with system typography).
    root.putClientProperty("apple.awt.windowTitleVisible", false)
}

/**
 * Homebrew OpenJDK can leave client properties on the JRootPane without
 * pushing TRANSPARENT_TITLE_BAR / FULL_WINDOW_CONTENT into the NSWindow.
 * Call [sun.lwawt.macosx.CPlatformWindow.setStyleBits] directly when the
 * peer exists. Bits must match CPlatformWindow (JDK 17).
 */
private fun applyMacStyleBitsViaPeer(window: Window) {
    if (!isMacOs()) return
    try {
        val accessor = Class.forName("sun.awt.AWTAccessor")
            .getMethod("getComponentAccessor")
            .invoke(null)
        val peer = accessor.javaClass.methods
            .first { it.name == "getPeer" && it.parameterCount == 1 }
            .invoke(accessor, window)
            ?: return
        val platformWindow = peer.javaClass.methods
            .first { it.name == "getPlatformWindow" && it.parameterCount == 0 }
            .invoke(peer)
            ?: return
        val setStyleBits = platformWindow.javaClass.getDeclaredMethod(
            "setStyleBits",
            Integer.TYPE,
            java.lang.Boolean.TYPE,
        )
        setStyleBits.isAccessible = true
        val fullWindowContent = 1 shl 14
        val transparentTitleBar = 1 shl 18
        val titleVisible = 1 shl 25
        setStyleBits.invoke(platformWindow, fullWindowContent, true)
        setStyleBits.invoke(platformWindow, transparentTitleBar, true)
        setStyleBits.invoke(platformWindow, titleVisible, false)
    } catch (_: Throwable) {
        // Missing --add-opens or non-mac peer: client properties stay the path.
    }
}
