package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-3: the **executable text-raster proof** for the commonMain watermark cell. Runs on
 * `:shared:desktopTest` and exercises [WatermarkCellComposer.composeTextCell] end-to-end on the
 * JVM/Skiko host: a platform-resolved [TextRasterEnv] (via [desktopTextRasterEnv]) measures +
 * paints a real text cell offscreen, and we assert the raster actually contains visible text
 * pixels (the non-blank gate, mirroring S4d-2's `renders_nonblank_pixels`).
 *
 * This proves the commonMain text path works — `TextMeasurer` measure + `MultiParagraph.paint`
 * render through a platform-injected resolver. It does NOT assert cross-platform pixel parity with
 * the Android `StaticLayout` raster; that is gated by the S4d-3 parity plan and must be re-proven
 * separately. (The Android production renderer is untouched by this slice.)
 */
class WatermarkTextCellRasterTest {

    private val env = desktopTextRasterEnv()

    @Test
    fun text_cell_renders_visible_pixels() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(env, content, degree = 0f)

        // White text on a transparent cell -> the raster must produce opaque/non-transparent pixels.
        val pixels = cell.toPixelMap()
        var nonBlank = 0
        var nonTransparent = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c != Color.Transparent) nonBlank++
                if (c.alpha > 0) nonTransparent++
            }
        }
        assertTrue(cell.width > 0 && cell.height > 0, "text cell must have positive dims")
        assertTrue(nonBlank > 0, "text cell must render visible (non-transparent) pixels")
        assertTrue(nonTransparent > 0, "text cell must render non-transparent (alpha>0) pixels")
    }

    @Test
    fun text_cell_dims_match_geometry_sizing() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(
            env, content, degree = 0f, hGapPercent = 0, vGapPercent = 0,
        )
        // The cell box = rotated-AABB of the measured text + 0% gap; re-derive it from the SAME
        // measurement path + WatermarkGeometry to anchor the sizing (not the exact pixel size,
        // which depends on the host font — kept out of the assertion).
        val measured = androidx.compose.ui.text.TextMeasurer(
            env.fontFamilyResolver,
            env.density,
            env.layoutDirection,
        ).measure(androidx.compose.ui.text.AnnotatedString(content.text), style = content.style)
        val expectedW = WatermarkGeometry.rotatedCellWidth(
            measured.size.width.toFloat(), measured.size.height.toFloat(), 0f,
        ).toInt()
        val expectedH = WatermarkGeometry.rotatedCellHeight(
            measured.size.width.toFloat(), measured.size.height.toFloat(), 0f,
        ).toInt()
        assertEquals(expectedW, cell.width)
        assertEquals(expectedH, cell.height)
    }

    @Test
    fun gap_100_doubles_each_axis_for_text() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val base = WatermarkCellComposer.composeTextCell(env, content, degree = 0f)
        val gapped = WatermarkCellComposer.composeTextCell(
            env, content, degree = 0f, hGapPercent = 100, vGapPercent = 100,
        )
        assertEquals(base.width * 2, gapped.width)
        assertEquals(base.height * 2, gapped.height)
    }

    /**
     * Placement / clipping regression guard (review round 1, P1). For degree=0 and no gap the cell
     * width equals the measured text width. `MultiParagraph.paint` draws from the canvas origin, so
     * the text box must be centred in the cell by translating its origin to
     * `(finalWidth - textWidth)/2` — NOT to `finalWidth/2`. Translating to `finalWidth/2` would
     * paint the paragraph starting at the cell centre and clip its right half.
     *
     * This asserts the visible-pixel column span crosses the cell's vertical centre line: there must
     * be visible columns strictly LEFT of `width/2` AND strictly RIGHT of `width/2`. The old
     * (buggy) `translate(left = finalWidth/2)` placement left all visible columns `>= width/2`
     * (everything painted from the centre rightward), so it fails this guard.
     */
    @Test
    fun text_is_box_centred_and_not_clipped_at_degree_0() {
        val content = WatermarkTextContent(
            text = "GOLDEN",
            style = TextStyle(fontSize = 24.sp),
            color = Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(
            env, content, degree = 0f, hGapPercent = 0, vGapPercent = 0,
        )
        val pixels = cell.toPixelMap()
        val midX = cell.width / 2
        var leftOfCenter = false
        var rightOfCenter = false
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0) {
                    if (x < midX) leftOfCenter = true
                    if (x > midX) rightOfCenter = true
                }
            }
        }
        assertTrue(leftOfCenter, "text must have visible pixels LEFT of the cell centre (not painted from centre onward)")
        assertTrue(rightOfCenter, "text must have visible pixels RIGHT of the cell centre (not clipped)")
    }
}
