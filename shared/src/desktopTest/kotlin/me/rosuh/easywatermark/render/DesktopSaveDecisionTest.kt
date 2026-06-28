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
}
