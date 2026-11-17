package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderNode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkOversizedCellCanvasTest {

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun exact5407x8109IconCell_capsBackingBitmapAndDrawsIntoRecordingCanvas() {
        val icon = Bitmap.createBitmap(511, 511, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef("test://oversized-icon"),
            textSize = 49f,
            hGap = 114,
            vGap = 221,
            degree = 315f,
            tileMode = WatermarkTileMode.REPEAT,
        )

        val raster = AndroidCommonRaster.composeCellRaster(
            context = context,
            config = config,
            imageWidth = 1080,
            icon = icon,
        )

        assertEquals(5407, raster.logicalWidth)
        assertEquals(8109, raster.logicalHeight)
        assertTrue(raster.bitmap.width <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(raster.bitmap.height <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        val allocationBytes = raster.bitmap.asAndroidBitmap().allocationByteCount
        assertTrue(
            "expected backing bitmap <= 16 MiB, got $allocationBytes bytes",
            allocationBytes <= MAX_CELL_BYTES,
        )

        recordTilesOnHardwareCanvas(raster, WatermarkTileMode.REPEAT)
    }

    @Test
    fun extremeIconAndTextCells_capBackingBitmapAndDrawIntoRecordingCanvas() {
        val icon = Bitmap.createBitmap(511, 511, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val extremeIconConfig = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef("test://extreme-icon"),
            textSize = 100f,
            hGap = 500,
            vGap = 500,
            degree = 45f,
            tileMode = WatermarkTileMode.REPEAT,
        )
        val iconRaster = AndroidCommonRaster.composeCellRaster(
            context = context,
            config = extremeIconConfig,
            imageWidth = 1080,
            icon = icon,
        )
        assertTrue(iconRaster.logicalWidth > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(iconRaster.logicalHeight > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(iconRaster.bitmap.width <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(iconRaster.bitmap.height <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(iconRaster.bitmap.asAndroidBitmap().allocationByteCount <= MAX_CELL_BYTES)
        recordTilesOnHardwareCanvas(iconRaster, WatermarkTileMode.REPEAT)
        recordTilesOnHardwareCanvas(iconRaster, WatermarkTileMode.CLAMP)

        val extremeTextConfig = WaterMark.default.copy(
            markMode = WatermarkMode.Text,
            text = "OVERSIZED WATERMARK CELL\nSECOND LONG LINE FOR BOUNDS",
            textSize = 100f,
            hGap = 500,
            vGap = 500,
            degree = 315f,
            tileMode = WatermarkTileMode.REPEAT,
        )
        val textRaster = AndroidCommonRaster.composeCellRaster(
            context = context,
            config = extremeTextConfig,
            imageWidth = 1080,
        )
        assertTrue(textRaster.logicalWidth > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(textRaster.logicalHeight > WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(textRaster.bitmap.width <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(textRaster.bitmap.height <= WatermarkCellComposer.MAX_CELL_RASTER_EDGE_PX)
        assertTrue(textRaster.bitmap.asAndroidBitmap().allocationByteCount <= MAX_CELL_BYTES)
        recordTilesOnHardwareCanvas(textRaster, WatermarkTileMode.REPEAT)
        recordTilesOnHardwareCanvas(textRaster, WatermarkTileMode.CLAMP)
    }

    @Test
    fun composeToBitmap_succeedsOnExtremeTextSizeAndGaps() {
        val bg = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.DKGRAY)
        }
        val info = ImageInfo.empty().apply {
            width = 1080
            height = 1920
        }
        val textConfig = WaterMark.default.copy(
            markMode = WatermarkMode.Text,
            text = "OVERSIZED WATERMARK CELL\nSECOND LONG LINE FOR BOUNDS",
            textSize = 100f,
            hGap = 500,
            vGap = 500,
            degree = 315f,
            tileMode = WatermarkTileMode.REPEAT,
        )
        val outText = AndroidCommonRaster.composeToBitmap(
            context = context,
            background = bg,
            config = textConfig,
            imageInfo = info,
        )
        assertEquals(1080, outText.width)
        assertEquals(1920, outText.height)

        val icon = Bitmap.createBitmap(511, 511, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val iconConfig = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef("test://extreme-icon"),
            textSize = 100f,
            hGap = 500,
            vGap = 500,
            degree = 45f,
            tileMode = WatermarkTileMode.REPEAT,
        )
        val outIcon = AndroidCommonRaster.composeToBitmap(
            context = context,
            background = bg,
            config = iconConfig,
            imageInfo = info,
            icon = icon,
        )
        assertEquals(1080, outIcon.width)
        assertEquals(1920, outIcon.height)
    }

    private fun recordTilesOnHardwareCanvas(
        raster: RasteredWatermarkCell,
        tileMode: WatermarkTileMode,
    ) {
        val renderNode = RenderNode("preview")
        val recordingCanvas = renderNode.beginRecording(1080, 1920)
        try {
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = Canvas(recordingCanvas),
                size = Size(1080f, 1920f),
            ) {
                drawWatermarkTiles(
                    cell = raster.bitmap,
                    tileMode = tileMode,
                    destWidth = 1080f,
                    destHeight = 1920f,
                    offsetX = 0.25f,
                    offsetY = 0.25f,
                    alpha = 1f,
                    tileWidth = raster.logicalWidth,
                    tileHeight = raster.logicalHeight,
                )
            }
        } finally {
            renderNode.endRecording()
        }
    }

    private companion object {
        const val MAX_CELL_BYTES = 16 * 1024 * 1024
    }
}
