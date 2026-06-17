package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S4d-4: the **executable icon-raster proof** for [WatermarkCellComposer.composeIconCell]. Runs on
 * `:shared:desktopTest` (JVM/Skiko host), the same home as S4d-3's `WatermarkTextCellRasterTest`.
 *
 * It builds a synthetic, **asymmetric** source [ImageBitmap] (blue with a red top-left quadrant) so
 * a no-rotation or wrong-centre implementation is observable, then asserts: visible pixels render;
 * cell dims follow `WatermarkGeometry.diagonal` + gap + `scaleRatio`; gap=100 doubles each axis;
 * rotation changes the rendered output; and the icon is centred (not drawn at the origin) at 0°.
 *
 * It does NOT assert cross-platform / cross-impl pixel parity with the Android
 * `Bitmap.createScaledBitmap(..., filter=false)` raster — that is gated separately (see
 * `parity-gate-plan.md`). The Android production renderer is untouched by this slice.
 */
class WatermarkIconCellRasterTest {

    /** Fully-opaque solid icon → covered pixels carry the source alpha (1.0) unless [alpha] reduces it. */
    private fun opaqueIcon(width: Int, height: Int): ImageBitmap {
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color = Color.Blue) // alpha == 1.0 everywhere
        }
        return bmp
    }

    /** Asymmetric icon: full blue, with a red top-left quadrant → rotation/centring is observable. */
    private fun syntheticIcon(width: Int, height: Int): ImageBitmap {
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color = Color.Blue)
            drawRect(color = Color.Red, topLeft = Offset.Zero, size = Size(width / 2f, height / 2f))
        }
        return bmp
    }

    /** Position-sensitive content hash so a rotation that has no effect is caught. */
    private fun ImageBitmap.contentHash(): Long {
        val px = toPixelMap()
        var h = 1125899906842597L
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                h = 31 * h + px[x, y].value.toLong()
            }
        }
        return h
    }

    @Test
    fun icon_cell_renders_visible_pixels() {
        val cell = WatermarkCellComposer.composeIconCell(syntheticIcon(40, 20), degree = 0f)
        assertTrue(cell.width > 0 && cell.height > 0, "icon cell must have positive dims")
        val px = cell.toPixelMap()
        var nonTransparent = 0
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                if (px[x, y].alpha > 0f) nonTransparent++
            }
        }
        assertTrue(nonTransparent > 0, "icon cell must render visible (non-transparent) pixels")
    }

    @Test
    fun icon_cell_dims_match_geometry_and_scale() {
        // scaleRatio 2f == textSize 28 / 14 (ICON_SCALE_REFERENCE_TEXT_SIZE), gap 0.
        val cell = WatermarkCellComposer.composeIconCell(syntheticIcon(40, 20), degree = 0f, scaleRatio = 2f)
        // Re-derive the SAME math: square cell = diagonal(rawHeight, rawWidth), gap 0, * scaleRatio.
        val maxSize = WatermarkGeometry.diagonal(20f, 40f)
        val expectedW = (WatermarkGeometry.horizontalGap(maxSize, 0) * 2f).toInt()
        val expectedH = (WatermarkGeometry.verticalGap(maxSize, 0) * 2f).toInt()
        assertEquals(expectedW, cell.width)
        assertEquals(expectedH, cell.height)
        // Icon cell is square at gap 0 (sized by the single diagonal value).
        assertEquals(cell.width, cell.height)
    }

    @Test
    fun gap_100_doubles_each_axis() {
        val icon = syntheticIcon(40, 20)
        val base = WatermarkCellComposer.composeIconCell(icon, degree = 0f, hGapPercent = 0, vGapPercent = 0, scaleRatio = 1f)
        val gapped = WatermarkCellComposer.composeIconCell(icon, degree = 0f, hGapPercent = 100, vGapPercent = 100, scaleRatio = 1f)
        assertEquals(base.width * 2, gapped.width)
        assertEquals(base.height * 2, gapped.height)
    }

    @Test
    fun rotation_changes_rendered_output() {
        val icon = syntheticIcon(40, 20)
        val flat = WatermarkCellComposer.composeIconCell(icon, degree = 0f)
        val rotated = WatermarkCellComposer.composeIconCell(icon, degree = 90f)
        // Square cell (dims unchanged by rotation), but the asymmetric content must move → the
        // rendered pixels differ. A no-rotation implementation would produce an identical hash.
        assertNotEquals(flat.contentHash(), rotated.contentHash(), "rotation must change rendered output")
    }

    @Test
    fun icon_is_centered_not_drawn_at_origin_at_degree_0() {
        // gap=100 makes the cell larger than the icon, so a correctly-centred icon leaves transparent
        // margins: the cell centre is opaque, the cell corner is transparent. Drawing at the origin
        // (a wrong-centre bug) would flip both.
        val cell = WatermarkCellComposer.composeIconCell(
            syntheticIcon(40, 20), degree = 0f, hGapPercent = 100, vGapPercent = 100, scaleRatio = 1f,
        )
        val px = cell.toPixelMap()
        val cx = cell.width / 2
        val cy = cell.height / 2
        assertTrue(px[cx, cy].alpha > 0f, "centred icon must cover the cell centre")
        assertEquals(Color.Transparent, px[0, 0], "cell corner must stay transparent (icon is centred, not at origin)")
    }

    /** Alpha at the centre of a flat, fully-opaque icon cell (the icon always covers the cell centre). */
    private fun centreAlpha(alpha: Float): Float {
        val cell = WatermarkCellComposer.composeIconCell(
            opaqueIcon(40, 40), degree = 0f, scaleRatio = 1f, alpha = alpha,
        )
        val px = cell.toPixelMap()
        return px[cell.width / 2, cell.height / 2].alpha
    }

    /**
     * P1 (review round 1): `composeIconCell` must honour the watermark opacity contract — Android draws
     * the icon with `Paint.alpha = WaterMark.alpha`, so the commonMain analogue must apply the
     * normalized [alpha]. These assertions FAIL for a full-opacity implementation that ignores alpha.
     */
    @Test
    fun alpha_scales_icon_opacity() {
        val full = centreAlpha(1f)
        val half = centreAlpha(0.5f)
        val zero = centreAlpha(0f)

        // Full opacity: the opaque icon stays (near-)opaque at the covered centre.
        assertTrue(full > 0.99f, "alpha=1f must keep the opaque icon opaque (was $full)")
        // alpha=0f: the covered centre must become fully transparent (a full-opacity impl leaves it opaque).
        assertTrue(zero < 0.01f, "alpha=0f must make covered pixels transparent (was $zero)")
        // alpha=0.5f: strictly reduced vs full and roughly half — not a weak "any visible pixel" check.
        assertTrue(half in 0.4f..0.6f, "alpha=0.5f must roughly halve opacity (was $half)")
        assertTrue(half < full, "alpha=0.5f must be strictly less opaque than alpha=1f ($half !< $full)")
        assertTrue(half > zero, "alpha=0.5f must be strictly more opaque than alpha=0f ($half !> $zero)")
    }

    /** Out-of-range alpha is clamped to 0f..1f at the raster boundary (no over/under-bright pixels). */
    @Test
    fun alpha_is_clamped_to_unit_range() {
        assertEquals(centreAlpha(1f), centreAlpha(2f), "alpha>1f must clamp to 1f")
        assertEquals(centreAlpha(0f), centreAlpha(-1f), "alpha<0f must clamp to 0f")
    }
}
