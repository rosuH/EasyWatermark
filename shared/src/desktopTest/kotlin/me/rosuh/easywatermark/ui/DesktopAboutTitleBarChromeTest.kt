package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop macOS title bar is transparent + fullWindowContent. Launch / About olive
 * (and About's radial halo) must paint under the traffic lights; only content that
 * would sit under the lights is inset. Client properties must be set in SwingWindow
 * init — after the window is displayable they are ignored (opaque black title bar).
 */
class DesktopAboutTitleBarChromeTest {

    @Test
    fun mac_title_bar_installed_in_swing_window_init() {
        val window = readFirst(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        )
        val chrome = readFirst(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindowChrome.kt",
        )
        assertTrue(
            window.contains("SwingWindow("),
            "must use SwingWindow so chrome can be installed before the window is displayable",
        )
        assertTrue(
            window.contains("init = {"),
            "mac title-bar client properties must be set in SwingWindow init",
        )
        val initAt = window.indexOf("init = {")
        val applyAt = window.indexOf("applyProductWindowChrome", initAt)
        assertTrue(
            initAt >= 0 && applyAt > initAt && applyAt < initAt + 240,
            "SwingWindow init must call applyProductWindowChrome before first show",
        )
        assertTrue(
            chrome.contains("apple.awt.fullWindowContent") &&
                chrome.contains("apple.awt.transparentTitleBar"),
            "macOS chrome must request fullWindowContent + transparent title bar",
        )
        assertTrue(
            chrome.contains("fun realizeMacTitleBarAfterShown") &&
                chrome.contains("setStyleBits"),
            "after first show must nudge the frame and push NSWindow style bits",
        )
        assertTrue(
            window.contains("realizeMacTitleBarAfterShown"),
            "Desktop window must realize mac title-bar chrome after first show",
        )
    }

    @Test
    fun launch_paints_under_title_bar_no_root_inset() {
        val window = readFirst(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        )
        val launchBlock = window.substringAfter("ProductShellNav.Route.Launch").substringBefore(
            "ProductShellNav.Route.About",
        )
        assertFalse(
            launchBlock.contains("macFullWindowContentInsets()"),
            "Launch root must not use macFullWindowContentInsets — that leaves a dark title band",
        )
        assertTrue(
            launchBlock.contains("LaunchScreen("),
            "Launch route must still host LaunchScreen",
        )
    }

    @Test
    fun about_paints_under_title_bar_content_only_inset() {
        val window = readFirst(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        )
        val about = readFirst(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/about/AboutScreenShell.kt",
        )
        val chrome = readFirst(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindowChrome.kt",
        )
        assertTrue(
            chrome.contains("fun macTitleBarContentPadding"),
            "title-bar inset for full-bleed screens must be content padding, not root padding",
        )
        assertTrue(
            window.contains("contentPadding = macTitleBarContentPadding()"),
            "Desktop About must inset content only so the halo reaches the title band",
        )
        assertTrue(
            about.contains(".padding(contentPadding)"),
            "AboutScreen content layer must honor contentPadding",
        )
        val aboutBlock = window.substringAfter("ProductShellNav.Route.About").substringBefore(
            "ProductShellNav.Route.Editor",
        )
        assertFalse(
            aboutBlock.contains("macFullWindowContentInsets()"),
            "About root must not use macFullWindowContentInsets — that clips the halo",
        )
        assertTrue(
            window.contains("productRoute == ProductShellNav.Route.About"),
            "covered Editor must not keep photo chrome on the About title band",
        )
    }

    private fun readFirst(vararg paths: String): String {
        val cwd = File("").absoluteFile
        val candidates = paths.flatMap { path ->
            listOf(File(path), File(cwd, path), File(cwd.parentFile, path))
        }
        return candidates.first { it.isFile }.readText()
    }
}
