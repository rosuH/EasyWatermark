package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class IosFontSelectionPreviewContractTest {

    @Test
    fun font_commit_repaints_and_overlay_resolves_request_font() {
        val text = resolveRepoFile(
            "shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt",
        ).readText()
        val apply = text.indexOf("change = WatermarkConfigChange.FontSelection")
        assertTrue(apply >= 0, "missing FontSelection apply")
        val window = text.substring(apply, (apply + 900).coerceAtMost(text.length))
        assertTrue(
            "renderPreviewForCurrentSelection" in window,
            "font commit must call renderPreviewForCurrentSelection",
        )
        assertTrue("fontAccess.resolve(wm.fontRef)" in text, "overlay must resolve wm.fontRef")
        assertTrue("resolvedFamily ?:" !in text, "must not silently fall back to cached Default family")
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
}
