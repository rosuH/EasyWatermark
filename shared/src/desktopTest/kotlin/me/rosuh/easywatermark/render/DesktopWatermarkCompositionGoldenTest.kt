package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The **Desktop composition gate** — proves [DesktopWatermarkComposer] composes a full * watermarked sample image over a background through the shared
 * [WatermarkCellComposer.composeOverBackground], in both REPEAT (tiled) and CLAMP (single-decal) modes.
 *
 * Like the text gate, it asserts **perceptual / structural** properties (NOT byte-exact host
 * pixels): output sized to the background, output differs from the background, REPEAT inks many
 * separated grid regions while CLAMP is localized to far fewer, both are deterministic (identical coarse
 * change-signature across two renders), and PNG encode is valid. Desktop-only; Android composition stays
 * native.
 */
class DesktopWatermarkCompositionGoldenTest {

    private val bgW = 256
    private val bgH = 192
    private val gridN = 8
    private val sampleText = "请勿转载\nDO NOT"

    /** Per-bucket boolean over an 8×8 grid: did ANY pixel change vs the background? */
    private fun changedGrid(composed: ImageBitmap, background: ImageBitmap): List<Boolean> {
        val c = composed.toPixelMap()
        val b = background.toPixelMap()
        val cellW = (c.width + gridN - 1) / gridN
        val cellH = (c.height + gridN - 1) / gridN
        val grid = ArrayList<Boolean>(gridN * gridN)
        for (gy in 0 until gridN) {
            for (gx in 0 until gridN) {
                var changed = false
                var y = gy * cellH
                loop@ while (y < minOf(gy * cellH + cellH, c.height)) {
                    var x = gx * cellW
                    while (x < minOf(gx * cellW + cellW, c.width)) {
                        if (c[x, y] != b[x, y]) { changed = true; break@loop }
                        x++
                    }
                    y++
                }
                grid.add(changed)
            }
        }
        return grid
    }

    private fun changedPixels(composed: ImageBitmap, background: ImageBitmap): Int {
        val c = composed.toPixelMap(); val b = background.toPixelMap()
        var n = 0
        for (y in 0 until c.height) for (x in 0 until c.width) if (c[x, y] != b[x, y]) n++
        return n
    }

    private fun background() = DesktopWatermarkComposer.sampleBackground(bgW, bgH)
    private fun repeatComposed() = DesktopWatermarkComposer.composeSample(
        sampleText, bgW, bgH, WatermarkTileMode.REPEAT,
    )
    private fun clampComposed() = DesktopWatermarkComposer.composeSample(
        sampleText, bgW, bgH, WatermarkTileMode.CLAMP, offsetX = 0.5f, offsetY = 0.5f,
    )

    @Test
    fun output_dimensions_equal_background() {
        val out = repeatComposed()
        assertEquals(bgW, out.width, "composed width must equal background width")
        assertEquals(bgH, out.height, "composed height must equal background height")
    }

    @Test
    fun repeat_differs_from_background_and_inks_multiple_separated_regions() {
        val bg = background()
        val out = repeatComposed()
        assertTrue(changedPixels(out, bg) > 0, "REPEAT output must differ from the background")
        val grid = changedGrid(out, bg)
        val changedBuckets = grid.count { it }
        // Tiling must spread ink across many buckets, and across multiple rows AND columns.
        assertTrue(changedBuckets >= 8, "REPEAT must ink many grid buckets (was $changedBuckets)")
        val rows = (0 until gridN).count { r -> (0 until gridN).any { col -> grid[r * gridN + col] } }
        val cols = (0 until gridN).count { col -> (0 until gridN).any { r -> grid[r * gridN + col] } }
        assertTrue(rows >= 2 && cols >= 2, "REPEAT ink must span ≥2 rows and ≥2 columns (rows=$rows cols=$cols)")
    }

    @Test
    fun clamp_differs_from_background_and_is_localized_vs_repeat() {
        val bg = background()
        val clamp = clampComposed()
        val repeat = repeatComposed()
        assertTrue(changedPixels(clamp, bg) > 0, "CLAMP output must differ from the background")
        val clampBuckets = changedGrid(clamp, bg).count { it }
        val repeatBuckets = changedGrid(repeat, bg).count { it }
        // A single decal must touch strictly fewer regions than the full tiling.
        assertTrue(
            clampBuckets < repeatBuckets,
            "CLAMP must be localized vs REPEAT (clamp=$clampBuckets, repeat=$repeatBuckets)",
        )
    }

    @Test
    fun outputs_deterministic_coarse_signature() {
        val bg = background()
        // REPEAT and CLAMP each reproduce the identical change-signature across two independent renders.
        assertEquals(
            changedGrid(repeatComposed(), bg), changedGrid(repeatComposed(), bg),
            "REPEAT composition must be deterministic (stable change-signature)",
        )
        assertEquals(
            changedGrid(clampComposed(), bg), changedGrid(clampComposed(), bg),
            "CLAMP composition must be deterministic (stable change-signature)",
        )
    }

    @Test
    fun unsupported_tile_modes_are_rejected() {
        // review P1: composeOverBackground supports REPEAT/CLAMP only. MIRROR/DECAL are not
        // product-exposed and their BitmapShader sampling is not reproduced by the draw loop, so they
        // must throw rather than silently alias to REPEAT (no false Android-parity claim).
        val bg = DesktopWatermarkComposer.sampleBackground(64, 64)
        val cell = DesktopWatermarkTextRenderer.renderTextCell("X", imageWidth = 64)
        for (mode in listOf(WatermarkTileMode.MIRROR, WatermarkTileMode.DECAL)) {
            assertFailsWith<IllegalArgumentException>("composeOverBackground must reject $mode") {
                WatermarkCellComposer.composeOverBackground(bg, cell, mode)
            }
        }
    }

    @Test
    fun png_encode_produces_valid_png_bytes() {
        val result = DesktopWatermarkComposer.composeSampleResult(sampleText, bgW, bgH, WatermarkTileMode.REPEAT)
        assertEquals(bgW, result.width); assertEquals(bgH, result.height)
        assertTrue(result.png.size > 8, "PNG output must be non-trivial")
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in magic.indices) assertEquals(magic[i], result.png[i], "PNG magic byte $i mismatch")
    }
}
