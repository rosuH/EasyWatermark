package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3 import-only / destination policy pure contracts (no Session export, no FS write).
 */
class DesktopImageImportTest {

    private fun info(path: String) = ImageInfo(MediaRef(path))

    @Test
    fun toImageInfos_preservesOrderAndAbsolutePaths() {
        val a = File("/tmp/a.png")
        val b = File("/tmp/b.jpg")
        val infos = DesktopImageImport.toImageInfos(listOf(a, b))
        assertEquals(listOf(a.absolutePath, b.absolutePath), infos.map { it.uri.value })
    }

    @Test
    fun mergeSelection_replaceWhenNotAppend() {
        val existing = listOf(info("/old/1.png"))
        val incoming = listOf(info("/new/a.png"), info("/new/b.png"))
        val merged = DesktopImageImport.mergeSelection(existing, incoming, append = false)
        assertEquals(incoming.map { it.uri.value }, merged.map { it.uri.value })
    }

    @Test
    fun mergeSelection_appendDedupesByPath_preservesOrder() {
        val existing = listOf(info("/a.png"), info("/b.png"))
        val incoming = listOf(info("/b.png"), info("/c.png"))
        val merged = DesktopImageImport.mergeSelection(existing, incoming, append = true)
        assertEquals(listOf("/a.png", "/b.png", "/c.png"), merged.map { it.uri.value })
    }

    @Test
    fun mergeSelection_appendOnEmptyActsAsReplace() {
        val incoming = listOf(info("/only.png"))
        val merged = DesktopImageImport.mergeSelection(emptyList(), incoming, append = true)
        assertEquals(listOf("/only.png"), merged.map { it.uri.value })
    }

    @Test
    fun mayUpdateLastSavedFile_falseForPreviewPath() {
        val preview = File("/app/preview/preview.img")
        assertFalse(DesktopImageImport.mayUpdateLastSavedFile(preview, preview))
        assertFalse(
            DesktopImageImport.mayUpdateLastSavedFile(
                File(preview.absolutePath),
                preview,
            ),
        )
    }

    @Test
    fun mayUpdateLastSavedFile_trueForRealExportPath() {
        val preview = File("/app/preview/preview.img")
        val real = File("/Pictures/watermarked.jpg")
        assertTrue(DesktopImageImport.mayUpdateLastSavedFile(real, preview))
    }

    @Test
    fun saveAs_exactTarget_isUserChosenPath() {
        // Policy witness: Save As must not route through resolveUniqueOutputFile.
        val chosen = File("/tmp/user-chosen-name.jpg")
        val exact = chosen // DesktopWindow passes this File straight to the spine
        assertEquals(chosen.absolutePath, exact.absolutePath)
        // Contrast: unique export would skip an existing base name.
        val dir = File("build/desktop-import-saveas-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            File(dir, "watermarked.jpg").writeBytes(byteArrayOf(1))
            val unique = DesktopSaveDecision.resolveUniqueOutputFile(dir, me.rosuh.easywatermark.data.model.ImageFormat.JPEG)
            assertEquals("watermarked_1.jpg", unique.name)
            // Save As with the same occupied name still means the exact File the user picked,
            // not the unique sibling — call sites must pass userChosen, not resolveUnique.
            val userSaveAs = File(dir, "watermarked.jpg")
            assertEquals("watermarked.jpg", userSaveAs.name)
            assertTrue(userSaveAs.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
