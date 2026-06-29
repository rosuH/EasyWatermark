package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.WatermarkMode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S4d-139 / S4d-222: unit tests for the [DesktopSaveDecision] seam extracted from
 * `DesktopWatermarkFlow.runSaveFlow`. This is the runtime harness for the Desktop flow glue that
 * `:shared:desktopTest` can reach (the flow itself lives in `:desktopApp`, which has no test source set).
 *
 * Most tests exercise pure decisions (no IO, no rendering). S4d-222 adds tests for
 * [resolveUniqueOutputFile], which performs filesystem existence checks only and does not create,
 * write, or delete files.
 */
class DesktopSaveDecisionTest {

    @Test
    fun text_mode_uses_text_plan_regardless_of_icon_path() {
        // Text mode is Text even if an icon path happens to be present (the flow renders text).
        assertEquals(DesktopRenderPlan.Text, DesktopSaveDecision.renderPlan(WatermarkMode.Text, ""))
        assertEquals(DesktopRenderPlan.Text, DesktopSaveDecision.renderPlan(WatermarkMode.Text, "/some/icon.png"))
    }

    @Test
    fun image_mode_with_nonempty_icon_uses_icon_plan_carrying_the_path() {
        assertEquals(
            DesktopRenderPlan.Icon("/tmp/icon.png"),
            DesktopSaveDecision.renderPlan(WatermarkMode.Image, "/tmp/icon.png"),
        )
    }

    @Test
    fun image_mode_with_empty_icon_fails_loudly_with_the_flow_message() {
        val e = assertFailsWith<IllegalArgumentException> {
            DesktopSaveDecision.renderPlan(WatermarkMode.Image, "")
        }
        // Same signal AND message the flow used inline (no silent fallback to Text).
        assertEquals(DesktopSaveDecision.EMPTY_ICON_MESSAGE, e.message)
        assertEquals(
            "Image-mode watermark has no persisted iconUri; refusing to render (no silent fallback to Text).",
            e.message,
        )
    }

    @Test
    fun default_output_filename_follows_format() {
        assertEquals("watermarked.jpg", DesktopSaveDecision.defaultOutputFileName(ImageFormat.JPEG))
        assertEquals("watermarked.png", DesktopSaveDecision.defaultOutputFileName(ImageFormat.PNG))
    }

    @Test
    fun uses_caller_input_reflects_presence_of_bytes() {
        assertTrue(DesktopSaveDecision.usesCallerInput(ByteArray(1)), "non-null bytes → caller input")
        assertTrue(DesktopSaveDecision.usesCallerInput(ByteArray(0)), "even empty (non-null) bytes → caller input")
        assertFalse(DesktopSaveDecision.usesCallerInput(null), "null → fixture default")
    }

    @Test
    fun resolve_unique_uses_base_name_when_dir_is_empty() {
        val dir = createTempDirectory().toFile()
        val result = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.JPEG)
        assertEquals(File(dir, "watermarked.jpg"), result)
    }

    @Test
    fun resolve_unique_adds_suffix_when_base_exists() {
        val dir = createTempDirectory().toFile()
        File(dir, "watermarked.jpg").createNewFile()
        val result = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.JPEG)
        assertEquals(File(dir, "watermarked_1.jpg"), result)
    }

    @Test
    fun resolve_unique_finds_smallest_available_suffix_with_multiple_collisions() {
        val dir = createTempDirectory().toFile()
        File(dir, "watermarked.jpg").createNewFile()
        File(dir, "watermarked_1.jpg").createNewFile()
        val result = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.JPEG)
        assertEquals(File(dir, "watermarked_2.jpg"), result)
    }

    @Test
    fun resolve_unique_fills_numbering_gap_when_lower_suffix_is_free() {
        val dir = createTempDirectory().toFile()
        File(dir, "watermarked.jpg").createNewFile()
        File(dir, "watermarked_2.jpg").createNewFile()
        val result = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.JPEG)
        assertEquals(File(dir, "watermarked_1.jpg"), result)
    }

    @Test
    fun resolve_unique_does_not_create_the_returned_file() {
        val dir = createTempDirectory().toFile()
        val result = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.PNG)
        assertFalse(result.exists(), "helper must not create the file")
    }

    @Test
    fun resolve_unique_is_format_independent() {
        val dir = createTempDirectory().toFile()
        File(dir, "watermarked.jpg").createNewFile()
        val pngResult = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.PNG)
        assertEquals(File(dir, "watermarked.png"), pngResult)
    }

    // --- S4d-228: multi-file drag/drop batch selection + sequential-naming contract ---

    @Test
    fun supported_image_files_keeps_supported_subset_in_order() {
        val exts = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif")
        val files = listOf(
            File("/a/photo.png"),
            File("/a/notes.txt"),
            File("/a/PIC.JPG"), // mixed-case extension still matches (lower-cased), order preserved
            File("/a/clip.gif"),
            File("/a/archive.zip"),
        )
        val result = DesktopSaveDecision.supportedImageFiles(files, exts)
        assertEquals(
            listOf(File("/a/photo.png"), File("/a/PIC.JPG"), File("/a/clip.gif")),
            result,
        )
    }

    @Test
    fun supported_image_files_empty_when_none_match() {
        val exts = setOf("png", "jpg")
        assertTrue(DesktopSaveDecision.supportedImageFiles(emptyList(), exts).isEmpty(), "empty input → empty")
        assertTrue(
            DesktopSaveDecision.supportedImageFiles(listOf(File("/a/x.txt"), File("/a/y.doc")), exts).isEmpty(),
            "no supported extensions → empty",
        )
    }

    @Test
    fun sequential_resolve_then_create_yields_distinct_names() {
        val dir = createTempDirectory().toFile()
        // Model the batch loop contract: resolve, WRITE the returned file, then resolve the next. Because
        // resolveUniqueOutputFile is existence-check-only, this sequence (mirroring runSaveFlow writing its
        // output before the next resolve) must produce distinct, collision-free names.
        val names = (1..3).map {
            val f = DesktopSaveDecision.resolveUniqueOutputFile(dir, ImageFormat.JPEG)
            f.createNewFile()
            f.name
        }
        assertEquals(listOf("watermarked.jpg", "watermarked_1.jpg", "watermarked_2.jpg"), names)
    }
}
