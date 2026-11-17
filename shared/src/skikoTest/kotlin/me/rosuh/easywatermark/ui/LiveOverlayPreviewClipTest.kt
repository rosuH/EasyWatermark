package me.rosuh.easywatermark.ui

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
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.drawWatermarkTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device bug: REPEAT overlay leaked one cell past a landscape Fit photo into the
 * letterbox below. Bake clips by bitmap size; the editor Canvas is the full pane.
 */
class LiveOverlayPreviewClipTest {

    @Test
    fun repeat_tiles_do_not_paint_letterbox_below_fit_photo() {
        val cell = solidBitmap(30, 30, Color.Red)
        val overlay = OverlayCell(
            cell = cell,
            tileMode = WatermarkTileMode.REPEAT,
            offsetX = 0f,
            offsetY = 0f,
            alpha = 1f,
            builtForWidth = 80,
        )
        val paneW = 80
        val paneH = 200
        val photoW = 80
        val photoH = 40
        val dest = fitDestRect(
            srcW = photoW.toFloat(),
            srcH = photoH.toFloat(),
            boxW = paneW.toFloat(),
            boxH = paneH.toFloat(),
        )!!
        val out = ImageBitmap(paneW, paneH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(paneW.toFloat(), paneH.toFloat()),
        ) {
            drawLiveOverlayLayer(photoW, photoH, overlay)
        }
        val pixels = out.toPixelMap()
        val destBottom = (dest.top + dest.height).toInt()
        var outside = 0
        for (y in destBottom until paneH) {
            for (x in 0 until paneW) {
                if (pixels[x, y] != Color.Transparent) outside++
            }
        }
        assertEquals(0, outside, "tiles leaked below Fit photo dest into letterbox")
        var inside = 0
        val destTop = dest.top.toInt()
        val destRight = (dest.left + dest.width).toInt().coerceAtMost(paneW)
        for (y in destTop until destBottom.coerceAtMost(paneH)) {
            for (x in dest.left.toInt() until destRight) {
                if (pixels[x, y] != Color.Transparent) inside++
            }
        }
        assertTrue(inside > 0, "overlay must still paint inside the Fit photo dest")
    }

    @Test
    fun downscaled_overlay_cell_tiles_at_logical_coordinates_and_clips_strictly_to_fit_dest() {
        val cell = ImageBitmap(20, 20, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(cell),
            size = Size(20f, 20f),
        ) {
            drawRect(color = Color.Red, topLeft = Offset.Zero, size = Size(10f, 10f))
        }
        val overlay = OverlayCell(
            cell = cell,
            tileMode = WatermarkTileMode.REPEAT,
            offsetX = 0f,
            offsetY = 0f,
            alpha = 1f,
            builtForWidth = 100,
            logicalWidth = 40,
            logicalHeight = 40,
        )
        val paneW = 100
        val paneH = 200
        val photoW = 100
        val photoH = 100
        val dest = fitDestRect(
            srcW = photoW.toFloat(),
            srcH = photoH.toFloat(),
            boxW = paneW.toFloat(),
            boxH = paneH.toFloat(),
        )!!
        val out = ImageBitmap(paneW, paneH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(paneW.toFloat(), paneH.toFloat()),
        ) {
            drawLiveOverlayLayer(photoW, photoH, overlay)
        }
        val pixels = out.toPixelMap()
        val destTop = dest.top.toInt()
        val destBottom = (dest.top + dest.height).toInt()
        var leaked = 0
        for (y in 0 until destTop) {
            for (x in 0 until paneW) {
                if (pixels[x, y] != Color.Transparent) leaked++
            }
        }
        for (y in destBottom until paneH) {
            for (x in 0 until paneW) {
                if (pixels[x, y] != Color.Transparent) leaked++
            }
        }
        assertEquals(0, leaked, "downscaled overlay tiles leaked into letterbox")
        // 20x20 cell scaled to 40x40 logical: red quadrant (0..10) maps to (0..20) in photo space.
        assertTrue(pixels[15, destTop + 15].alpha > 0f, "tile 0 must scale up to logical bounds")
        assertEquals(
            Color.Transparent,
            pixels[25, destTop + 15],
            "horizontal gap (20..40) must remain empty rather than stepping by bitmap width 20",
        )
        assertTrue(pixels[45, destTop + 5].alpha > 0f, "next horizontal tile must start at logical x=40")
        assertEquals(
            Color.Transparent,
            pixels[5, destTop + 25],
            "vertical gap (20..40) must remain empty rather than stepping by bitmap height 20",
        )
        assertTrue(pixels[5, destTop + 45].alpha > 0f, "next vertical tile must start at logical y=40")
    }

    @Test
    fun centered_draw_box_tiles_accurately_across_repeat_grid_without_clipping() {
        val cell = solidBitmap(20, 20, Color.Red)
        val overlay = OverlayCell(
            cell = cell,
            tileMode = WatermarkTileMode.REPEAT,
            offsetX = 0f,
            offsetY = 0f,
            alpha = 1f,
            builtForWidth = 120,
            logicalWidth = 60,
            logicalHeight = 60,
            drawWidth = 20,
            drawHeight = 20,
        )
        val paneW = 120
        val paneH = 240
        val photoW = 120
        val photoH = 120
        val dest = fitDestRect(
            srcW = photoW.toFloat(),
            srcH = photoH.toFloat(),
            boxW = paneW.toFloat(),
            boxH = paneH.toFloat(),
        )!!
        val out = ImageBitmap(paneW, paneH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(paneW.toFloat(), paneH.toFloat()),
        ) {
            drawLiveOverlayLayer(photoW, photoH, overlay)
        }
        val pixels = out.toPixelMap()
        val destTop = dest.top.toInt()
        val destBottom = (dest.top + dest.height).toInt()
        for (y in 0 until paneH) {
            for (x in 0 until paneW) {
                if (y < destTop || y >= destBottom) {
                    assertEquals(Color.Transparent, pixels[x, y], "letterbox at ($x,$y) must stay transparent")
                } else {
                    val photoY = y - destTop
                    val inTileContent = (x % 60) in 20 until 40 && (photoY % 60) in 20 until 40
                    val expected = if (inTileContent) Color.Red else Color.Transparent
                    assertEquals(
                        expected,
                        pixels[x, y],
                        "repeat tile pixel at ($x,$y) (photo ($x,$photoY)) mismatch",
                    )
                }
            }
        }
    }

    @Test
    fun centered_draw_box_places_clamp_tile_accurately_and_scales_downscaled_cell_without_clipping() {
        val cell = solidBitmap(20, 20, Color.Blue)
        val overlay = OverlayCell(
            cell = cell,
            tileMode = WatermarkTileMode.CLAMP,
            offsetX = 0.25f,
            offsetY = 0.25f,
            alpha = 1f,
            builtForWidth = 120,
            logicalWidth = 60,
            logicalHeight = 60,
            drawWidth = 20,
            drawHeight = 20,
        )
        val paneW = 120
        val paneH = 240
        val photoW = 120
        val photoH = 120
        val dest = fitDestRect(
            srcW = photoW.toFloat(),
            srcH = photoH.toFloat(),
            boxW = paneW.toFloat(),
            boxH = paneH.toFloat(),
        )!!
        val out = ImageBitmap(paneW, paneH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(paneW.toFloat(), paneH.toFloat()),
        ) {
            drawLiveOverlayLayer(photoW, photoH, overlay)
        }
        val pixels = out.toPixelMap()
        val destTop = dest.top.toInt()
        // CLAMP origin = (30, 30); pad = (20, 20); centered 20x20 box occupies photo [50..70) x [50..70).
        for (y in 0 until paneH) {
            for (x in 0 until paneW) {
                val photoY = y - destTop
                val inContent = x in 50 until 70 && photoY in 50 until 70
                val expected = if (inContent) Color.Blue else Color.Transparent
                assertEquals(expected, pixels[x, y], "CLAMP pixel at ($x,$y) mismatch")
            }
        }

        // Also verify drawWatermarkTiles directly when backing bitmap is smaller than drawWidth x drawHeight.
        val scaledCell = solidBitmap(10, 10, Color.Green)
        val directOut = ImageBitmap(photoW, photoH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(directOut),
            size = Size(photoW.toFloat(), photoH.toFloat()),
        ) {
            drawWatermarkTiles(
                cell = scaledCell,
                tileMode = WatermarkTileMode.CLAMP,
                destWidth = photoW.toFloat(),
                destHeight = photoH.toFloat(),
                offsetX = 0.25f,
                offsetY = 0.25f,
                alpha = 1f,
                tileWidth = 60,
                tileHeight = 60,
                drawWidth = 20,
                drawHeight = 20,
            )
        }
        val directPixels = directOut.toPixelMap()
        for (y in 0 until photoH) {
            for (x in 0 until photoW) {
                val expected = if (x in 50 until 70 && y in 50 until 70) Color.Green else Color.Transparent
                assertEquals(expected, directPixels[x, y], "scaled drawWatermarkTiles CLAMP mismatch at ($x,$y)")
            }
        }
    }

    private fun solidBitmap(w: Int, h: Int, color: Color): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color)
        }
        return bmp
    }
}
