package me.rosuh.easywatermark.ui

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
