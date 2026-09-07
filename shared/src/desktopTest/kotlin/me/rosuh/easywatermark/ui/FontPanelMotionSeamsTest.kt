package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Font panel appear / disappear / inner-viewport motion contracts.
 * Source-level: no device, no screenshot.
 */
class FontPanelMotionSeamsTest {

    @Test
    fun style_unsupported_hint_is_gone() {
        val session = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/FontPanelSession.kt",
        )
        val panel = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/compose/FontPanel.kt",
        )
        val ui = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/FontPanelUi.kt",
        )
        val sharedStrings = read(
            "shared/src/commonMain/composeResources/values/strings.xml",
        )
        val appStrings = read("app/src/main/res/values/strings.xml")
        assertFalse(session.contains("This font does not support every bold/italic style."))
        assertFalse(panel.contains("styleHint") || panel.contains("editorFontStyleHint"))
        assertFalse(ui.contains("styleHint"))
        assertFalse(sharedStrings.contains("font_style_unsupported"))
        assertFalse(appStrings.contains("font_style_unsupported"))
    }

    @Test
    fun sheet_and_dialog_wait_for_exit() {
        val host = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/EditorFontSheetHost.kt",
        )
        assertTrue(host.contains("sheetState.hide()"), "sheet Done must hide before dispose")
        assertTrue(host.contains("closeRequest"), "dialog Done must bump closeRequest")
        assertTrue(host.contains("optionPanelSlideMs"), "sheet Off path honors MotionPolicy")
        assertTrue(host.contains("motionDurationMs"), "host scales durations")
        assertFalse(
            Regex("""if\s*\(\s*event\s+is\s+FontPanelEvent\.Dismiss\s*\)\s*\{\s*dismiss\(\)""").containsMatchIn(host),
            "Dismiss must not unmount the host immediately",
        )
    }

    @Test
    fun inner_viewport_fades_without_height_animation() {
        val panel = read(
            "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/compose/FontPanel.kt",
        )
        assertTrue(panel.contains("AnimatedContent"), "tab/mode swaps fade")
        assertTrue(panel.contains("optionPanelFadeMs"), "inner fade uses existing token")
        assertTrue(panel.contains("SizeTransform"), "do not let AnimatedContent animate bounds")
        assertTrue(panel.contains("tween<IntSize>(durationMillis = 0)"), "viewport size stays snap")
        assertTrue(panel.contains("animateItem("), "search row insert/remove fades in place")
        assertTrue(panel.contains("expandVertically"), "footer/unavailable occupy inner space")
        assertTrue(panel.contains("contentSizeMs"), "inner space uses contentSizeMs")
        assertTrue(panel.contains(".height(budget)"), "outer panel height stays window-based")
        assertFalse(panel.contains("animateContentSize"), "do not animate the sheet height")
        assertTrue(panel.contains("FastOutSlowInEasing"), "existing easing, not a new curve")
        assertTrue(panel.contains("motionDurationMs"), "inner motion honors MotionPolicy")
    }

    private fun resolveRepoFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir")!!)
        val candidates = listOf(
            File(cwd, relative),
            File(cwd.parentFile, relative),
            File(cwd, "../$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("$relative not found from user.dir=$cwd")
    }

    private fun read(path: String): String = resolveRepoFile(path).readText()
}
