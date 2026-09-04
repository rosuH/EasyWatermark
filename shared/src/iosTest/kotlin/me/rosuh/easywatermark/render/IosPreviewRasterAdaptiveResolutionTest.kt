package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adaptive committed preview long-edge: 1440 bucket produces a 1440-wide raster; no export files.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPreviewRasterAdaptiveResolutionTest {

    @Test
    fun committedPreview_maxEdge1440_rasterMatchesBucket_andWritesNoExport() {
        val dir = NSTemporaryDirectory()
        val before = listEwmOut(dir)

        val sourcePath = dir + "c3_adapt_src_" + NSUUID().UUIDString() + ".png"
        // 2048 long edge → with maxEdge 1440 decodes to long edge 1440.
        val sourceBytes = IosWatermarkRenderer.encodePng(solid(2048, 1536, Color(0xFF304050)))
        assertTrue(IosByteArrayInterop.toNSData(sourceBytes).writeToFile(sourcePath, atomically = true))

        val iconPath = dir + "c3_adapt_icon_" + NSUUID().UUIDString() + ".png"
        val iconBytes = IosWatermarkRenderer.encodePng(solid(32, 24, Color.Red))
        assertTrue(IosByteArrayInterop.toNSData(iconBytes).writeToFile(iconPath, atomically = true))

        val wm = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 14f,
            degree = 0f,
            alpha = 255,
        )
        val preview = IosPreviewRaster.renderWatermarked(
            sourcePath = sourcePath,
            waterMark = wm,
            offsetX = 0.5f,
            offsetY = 0.5f,
            maxEdgePx = PreviewResolutionPolicy.BUCKET_1440,
        )
        assertEquals(1440, preview.width)
        assertEquals(1080, preview.height)

        val after = listEwmOut(dir)
        assertTrue(
            after.subtract(before).isEmpty(),
            "1440 committed preview must not write ewm_out_*; new=${after.subtract(before)}",
        )
    }

    @Test
    fun policy_1206pxBox_selects1440() {
        assertEquals(1440, PreviewResolutionPolicy.committedMaxEdgePx(1206, 800))
    }

    private fun listEwmOut(tmp: String): Set<String> {
        val path = tmp.trimEnd('/')
        @Suppress("UNCHECKED_CAST")
        val contents = platform.Foundation.NSFileManager.defaultManager
            .contentsOfDirectoryAtPath(path, error = null) as? List<*>
            ?: error("listEwmOut failed for $path")
        return contents.mapNotNull { it as? String }
            .filter { it.startsWith("ewm_out_") }
            .toSet()
    }

    private fun solid(w: Int, h: Int, color: Color): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return bmp
    }
}
