package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop macOS title bar is transparent + fullWindowContent. About's olive + radial
 * halo must paint under the traffic lights; only content is inset.
 */
class DesktopAboutTitleBarChromeTest {

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
