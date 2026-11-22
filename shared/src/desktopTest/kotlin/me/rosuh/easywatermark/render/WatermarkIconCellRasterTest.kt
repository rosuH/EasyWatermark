package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.FilterQuality
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The **executable icon-raster proof** for [WatermarkCellComposer.composeIconCell]. Runs on * `:shared:desktopTest` (JVM/Skiko host), the same home as 's `WatermarkTextCellRasterTest`.
 *
 * It builds a synthetic, **asymmetric** source [ImageBitmap] (blue with a red top-left quadrant) so
 * a no-rotation or wrong-centre implementation is observable, then asserts: visible pixels render;
 * cell dims follow `WatermarkGeometry.diagonal` + gap + `scaleRatio`; gap=100 doubles each axis;
 * rotation changes the rendered output; and the icon is centred (not drawn at the origin) at 0°.
 *
 * It does NOT assert cross-platform / cross-impl pixel parity with the native Android
 * `Bitmap.createScaledBitmap(..., filter=false)` oracle — that is gated separately (see
 * `parity-gate-plan.md`). Production Android/Desktop/iOS use this common icon path (ADR-0018);
 * native `WatermarkRenderer` remains comparison/golden oracle only.
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
 * The icon with `Paint.alpha = WaterMark.alpha`, so the commonMain analogue must apply the * normalized [alpha]. These assertions FAIL for a full-opacity implementation that ignores alpha.
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

    @Test
    fun exact_5407x8109_oversized_icon_cell_caps_backing_bitmap_and_preserves_logical_dims() {
        val icon = syntheticIcon(511, 511)
        val raster = WatermarkCellComposer.composeIconCellRaster(
            icon = icon,
            degree = 315f,
            hGapPercent = 114,
            vGapPercent = 221,
            scaleRatio = 49f / 14f,
        )
        assertEquals(5407, raster.logicalWidth)
        assertEquals(8109, raster.logicalHeight)
        assertTrue(
            raster.bitmap.width <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                raster.bitmap.height <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "backing bitmap must cap at ${WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX}, " +
                "got ${raster.bitmap.width}x${raster.bitmap.height}",
        )
        assertEquals(
            WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            maxOf(raster.bitmap.width, raster.bitmap.height),
        )
        val px = raster.bitmap.toPixelMap()
        assertTrue(
            px[raster.bitmap.width / 2, raster.bitmap.height / 2].alpha > 0f,
            "oversized icon cell must still render centered non-transparent pixels",
        )
        assertEquals(Color.Transparent, px[0, 0], "gap corner must remain transparent")
    }

    @Test
    fun extreme_gap_and_scale_icon_cell_caps_backing_bitmap_and_preserves_logical_dims() {
        val icon = syntheticIcon(511, 511)
        val scaleRatio = 100f / 14f
        val diagonal = WatermarkGeometry.diagonal(511f, 511f)
        val expectedLogicalW = (WatermarkGeometry.horizontalGap(diagonal, 500).toDouble() * scaleRatio.toDouble())
            .toFloat().toInt()
        val expectedLogicalH = (WatermarkGeometry.verticalGap(diagonal, 500).toDouble() * scaleRatio.toDouble())
            .toFloat().toInt()

        val raster = WatermarkCellComposer.composeIconCellRaster(
            icon = icon,
            degree = 0f,
            hGapPercent = 500,
            vGapPercent = 500,
            scaleRatio = scaleRatio,
        )
        assertEquals(expectedLogicalW, raster.logicalWidth)
        assertEquals(expectedLogicalH, raster.logicalHeight)
        assertTrue(raster.logicalWidth > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(raster.logicalHeight > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(
            raster.bitmap.width <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                raster.bitmap.height <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "extreme backing bitmap must cap at ${WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX}, " +
                "got ${raster.bitmap.width}x${raster.bitmap.height}",
        )
        val px = raster.bitmap.toPixelMap()
        assertTrue(
            px[raster.bitmap.width / 2, raster.bitmap.height / 2].alpha > 0f,
            "extreme oversized icon cell must render centered non-transparent pixels",
        )
        assertEquals(Color.Transparent, px[0, 0], "gap corner must remain transparent")
    }

    @Test
    fun sparse_gap_icon_cell_keeps_sharp_draw_box_and_aligns_center() {
        val icon = syntheticIcon(256, 256)
        val degree = 315f
        val hGap = 400
        val vGap = 400
        val scaleRatio = 2f
        val raster = WatermarkCellComposer.composeIconCellRaster(
            icon = icon,
            degree = degree,
            hGapPercent = hGap,
            vGapPercent = vGap,
            scaleRatio = scaleRatio,
        )

        assertTrue(
            raster.logicalWidth > 3000 && raster.logicalHeight > 3000,
            "sparse-gap icon fixture must exceed 3000 logically (was ${raster.logicalWidth}x${raster.logicalHeight})",
        )
        assertTrue(
            raster.drawWidth <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX &&
                raster.drawHeight <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX,
            "rotated icon content box must fit inside 2048 (was ${raster.drawWidth}x${raster.drawHeight})",
        )
        assertEquals(
            raster.drawWidth,
            raster.bitmap.width,
            "sparse-gap icon must raster 1:1 without downscaling",
        )
        assertEquals(
            raster.drawHeight,
            raster.bitmap.height,
            "sparse-gap icon must raster 1:1 without downscaling",
        )
        assertTrue(raster.bitmap.width < raster.logicalWidth)
        assertTrue(raster.bitmap.height < raster.logicalHeight)
        assertEquals(Color.Transparent, raster.bitmap.toPixelMap()[0, 0], "4 px safety margin must keep corner transparent")

        val bg = ImageBitmap(raster.logicalWidth, raster.logicalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bg),
            size = Size(raster.logicalWidth.toFloat(), raster.logicalHeight.toFloat()),
        ) {
            drawRect(Color.Black)
        }
        val composed = WatermarkCellComposer.composeOverBackground(
            background = bg,
            cell = raster.bitmap,
            tileMode = WatermarkTileMode.REPEAT,
            offsetX = 0f,
            offsetY = 0f,
            alpha = 1f,
            tileWidth = raster.logicalWidth,
            tileHeight = raster.logicalHeight,
            drawWidth = raster.drawWidth,
            drawHeight = raster.drawHeight,
        )

        val scaledW = (icon.width * scaleRatio).toInt()
        val scaledH = (icon.height * scaleRatio).toInt()
        val scaledIcon = ImageBitmap(scaledW, scaledH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(scaledIcon),
            size = Size(scaledW.toFloat(), scaledH.toFloat()),
        ) {
            drawImage(
                image = icon,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(icon.width, icon.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(scaledW, scaledH),
                filterQuality = FilterQuality.None,
            )
        }
        val manualCell = ImageBitmap(raster.logicalWidth, raster.logicalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(manualCell),
            size = Size(raster.logicalWidth.toFloat(), raster.logicalHeight.toFloat()),
        ) {
            rotate(degrees = degree, pivot = Offset(raster.logicalWidth / 2f, raster.logicalHeight / 2f)) {
                drawImage(
                    image = scaledIcon,
                    topLeft = Offset(
                        (raster.logicalWidth - scaledW) / 2f,
                        (raster.logicalHeight - scaledH) / 2f,
                    ),
                )
            }
        }
        val manual = ImageBitmap(raster.logicalWidth, raster.logicalHeight, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(manual),
            size = Size(raster.logicalWidth.toFloat(), raster.logicalHeight.toFloat()),
        ) {
            drawImage(bg, topLeft = Offset.Zero)
            drawImage(manualCell, topLeft = Offset.Zero)
        }

        val compPx = composed.toPixelMap()
        val manPx = manual.toPixelMap()
        assertEquals(Color.Black, compPx[0, 0], "outer gap corner must remain background")
        val cx = raster.logicalWidth / 2
        val cy = raster.logicalHeight / 2
        assertTrue(compPx[cx, cy].blue > 0.9f || compPx[cx, cy].red > 0.9f, "centered icon must render at tile center")

        var compInk = 0
        var manInk = 0
        var compSumX = 0.0
        var compSumY = 0.0
        var manSumX = 0.0
        var manSumY = 0.0
        var compRedCount = 0
        var manRedCount = 0
        var compRedSumX = 0.0
        var compRedSumY = 0.0
        var manRedSumX = 0.0
        var manRedSumY = 0.0
        var compMinX = Int.MAX_VALUE
        var compMaxX = -1
        var compMinY = Int.MAX_VALUE
        var compMaxY = -1
        var manMinX = Int.MAX_VALUE
        var manMaxX = -1
        var manMinY = Int.MAX_VALUE
        var manMaxY = -1
        var maxDelta = 0f
        for (y in 0 until raster.logicalHeight) {
            for (x in 0 until raster.logicalWidth) {
                val cc = compPx[x, y]
                val mc = manPx[x, y]
                maxDelta = max(maxDelta, max(abs(cc.red - mc.red), abs(cc.blue - mc.blue)))
                if (cc.red > 0.05f || cc.blue > 0.05f) {
                    compInk++
                    compSumX += x
                    compSumY += y
                    compMinX = min(compMinX, x)
                    compMaxX = max(compMaxX, x)
                    compMinY = min(compMinY, y)
                    compMaxY = max(compMaxY, y)
                    if (cc.red > 0.5f && cc.blue < 0.2f) {
                        compRedCount++
                        compRedSumX += x
                        compRedSumY += y
                    }
                }
                if (mc.red > 0.05f || mc.blue > 0.05f) {
                    manInk++
                    manSumX += x
                    manSumY += y
                    manMinX = min(manMinX, x)
                    manMaxX = max(manMaxX, x)
                    manMinY = min(manMinY, y)
                    manMaxY = max(manMaxY, y)
                    if (mc.red > 0.5f && mc.blue < 0.2f) {
                        manRedCount++
                        manRedSumX += x
                        manRedSumY += y
                    }
                }
            }
        }
        assertTrue(compInk > 0 && manInk > 0 && compRedCount > 0 && manRedCount > 0)
        assertTrue(
            abs(compSumX / compInk - manSumX / manInk) < 0.25 &&
                abs(compSumY / compInk - manSumY / manInk) < 0.25,
            "icon centroid must align identically at center",
        )
        assertTrue(
            abs(compRedSumX / compRedCount - manRedSumX / manRedCount) < 0.25 &&
                abs(compRedSumY / compRedCount - manRedSumY / manRedCount) < 0.25,
            "rotated red quadrant centroid must align identically at center",
        )
        assertTrue(
            abs(compMinX - manMinX) <= 1 && abs(compMaxX - manMaxX) <= 1 &&
                abs(compMinY - manMinY) <= 1 && abs(compMaxY - manMaxY) <= 1,
            "icon bounding box must match un-capped render",
        )
        assertTrue(
            maxDelta <= 0.02f,
            "1:1 drawWidth icon pixels must match un-capped manual draw without downscale blur (maxDelta=$maxDelta)",
        )
    }

    @Test
    fun content_dims_match_scaled_icon_across_rotations() {
        val icon = syntheticIcon(40, 20)
        val deg0 = WatermarkCellComposer.composeIconCellRaster(icon, degree = 0f, scaleRatio = 2f)
        val deg45 = WatermarkCellComposer.composeIconCellRaster(icon, degree = 45f, scaleRatio = 2f)
        val deg90 = WatermarkCellComposer.composeIconCellRaster(icon, degree = 90f, scaleRatio = 2f)
        assertEquals(80f, deg0.contentWidth, 0.5f)
        assertEquals(40f, deg0.contentHeight, 0.5f)
        assertEquals(deg0.contentWidth, deg45.contentWidth, 0.5f)
        assertEquals(deg0.contentHeight, deg45.contentHeight, 0.5f)
        assertEquals(deg0.contentWidth, deg90.contentWidth, 0.5f)
        assertEquals(deg0.contentHeight, deg90.contentHeight, 0.5f)
    }
}
