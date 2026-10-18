package me.rosuh.easywatermark.ui

import java.io.File
import kotlin.test.*

/** Structural ownership checks; actual touch behavior is covered by PreviewGestureUiTest. */
class ClampPreviewOffsetHostWiringTest {
    private fun read(path: String): String {
        val cwd = File(System.getProperty("user.dir")!!)
        return listOf(File(cwd, path), File(cwd.parentFile, path)).first { it.isFile }.readText()
    }

    private fun callback(source: String): String = source.substringAfter("onOffsetCommit = {").substringBefore("renderGestureCell =")

    @Test
    fun desktop_commit_keeps_session_then_one_committed_paint() {
        val source = read("desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt")
        val commit = callback(source)
        assertTrue("curImageInfo" in commit && "it.uri == dragItem?.uri" in commit)
        assertEquals(1, Regex("session\\.applyOffset\\(").findAll(commit).count())
        assertEquals(1, Regex("offsetPreviewGeneration\\+\\+").findAll(commit).count())
        assertEquals(1, Regex("paintConflator\\.submit\\(").findAll(commit).count())
        assertTrue(commit.indexOf("session.applyOffset") < commit.indexOf("offsetPreviewGeneration++"))
        assertTrue("isDraft = false" in commit)
        assertFalse("overlayCell = null" in commit || "previewPhoto = null" in commit || "runSaveFlow" in commit)
        assertFalse("repo.updateOffset" in source || "detectDragGestures" in source)
        val request = read("shared/src/desktopMain/kotlin/me/rosuh/easywatermark/render/DesktopRenderSaveSpine.kt")
        val offsets = request.lineSequence().filter { it.trim().startsWith("val offsetX") || it.trim().startsWith("val offsetY") }.toList()
        assertEquals(2, offsets.size)
        assertTrue(offsets.none { "=" in it }, "export must require the actual Session offsets")
    }

    @Test
    fun ios_commit_checks_identity_and_keeps_live_layers() {
        val source = read("shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui/IosProductRootHost.kt")
        val commit = callback(source)
        assertTrue("curImageInfo" in commit && "it.uri.value == dragPath" in commit)
        assertTrue("previewSourcePath == dragPath" in commit)
        assertEquals(1, Regex("session\\.applyOffset\\(").findAll(commit).count())
        assertTrue(commit.indexOf("session.applyOffset") < commit.indexOf("previewGen++"))
        assertEquals(1, Regex("renderPreviewForCurrentSelection\\(").findAll(commit).count())
        assertFalse("overlayCell = null" in commit || "previewBitmap = null" in commit)
        assertFalse("detectDragGestures" in source || ".clampPreviewOffsetDrag(" in source)
    }

    @Test
    fun android_commit_enqueues_one_non_draft_and_drops_all_stale_paints() {
        val source = read("app/src/main/java/me/rosuh/easywatermark/ui/AndroidEditorScreen.kt")
        val commit = callback(source)
        assertEquals(1, Regex("onOffsetChanged\\(").findAll(commit).count())
        assertEquals(1, Regex("submitCommittedOverlayPaint\\(").findAll(commit).count())
        assertTrue(commit.indexOf("onOffsetChanged") < commit.indexOf("submitCommittedOverlayPaint"))
        assertFalse("overlay = null" in commit || "livePhoto = null" in commit || "composeToBitmap" in commit)
        val helper = source.substringAfter("fun submitCommittedOverlayPaint").substringBefore("paintHandler.value")
        assertTrue("paintToken += 1" in helper && "isDraft = false" in helper && "paintConflator.submit" in helper)
        assertTrue(Regex("if \\(req.token != paintToken\\) return@paint").findAll(source).count() >= 2)
        assertFalse("detectDragGestures" in source)
    }
}
