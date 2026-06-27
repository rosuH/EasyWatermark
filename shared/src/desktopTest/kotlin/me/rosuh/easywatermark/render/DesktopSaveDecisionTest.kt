package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.WatermarkMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S4d-139: unit tests for the pure [DesktopSaveDecision] seam extracted from
 * `DesktopWatermarkFlow.runSaveFlow`. This is the runtime harness for the Desktop flow glue that
 * `:shared:desktopTest` can reach (the flow itself lives in `:desktopApp`, which has no test source set).
 * Pure decisions only — no IO, no rendering.
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
}
