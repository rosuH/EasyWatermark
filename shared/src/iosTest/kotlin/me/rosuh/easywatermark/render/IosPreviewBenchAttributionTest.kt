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
import me.rosuh.easywatermark.data.model.WaterMark
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1: the switch decomposition depends on [IosPreviewBench.Attribution] collecting raster stage
 * time across the `Dispatchers.Default` hop, and on staying closed outside a window so ±2 neighbor
 * warming is never charged to a switch.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPreviewBenchAttributionTest {

    @Test
    fun attribution_collectsDecodeAndCompose_onlyInsideWindow() {
        val sourcePath = writeSource(640, 480)

        IosPreviewBench.Attribution.begin()
        IosPreviewRaster.renderWatermarked(
            sourcePath = sourcePath,
            waterMark = WaterMark.default,
            maxEdgePx = 320,
        )
        val inside = IosPreviewBench.Attribution.end()

        assertTrue(
            inside.containsKey(IosPreviewBench.STAGE_IMAGE_IO),
            "decode stage must be attributed inside the window (got $inside)",
        )
        assertTrue(
            inside.containsKey(IosPreviewBench.STAGE_COMPOSE),
            "compose stage must be attributed inside the window (got $inside)",
        )

        // Outside the window (the ±2 prefetch case) nothing accumulates.
        IosPreviewRaster.renderWatermarked(
            sourcePath = sourcePath,
            waterMark = WaterMark.default,
            maxEdgePx = 320,
        )
        assertEquals(
            inside,
            IosPreviewBench.Attribution.snapshot(),
            "raster after end() must not change the closed window's totals",
        )
    }

    @Test
    fun attribution_begin_dropsPreviousTotals() {
        val sourcePath = writeSource(320, 240)

        IosPreviewBench.Attribution.begin()
        IosPreviewRaster.decodeSourcePlaceholder(sourcePath, maxEdgePx = 160)
        IosPreviewRaster.decodeSourcePlaceholder(sourcePath, maxEdgePx = 160)
        val two = IosPreviewBench.Attribution.end()

        IosPreviewBench.Attribution.begin()
        IosPreviewRaster.decodeSourcePlaceholder(sourcePath, maxEdgePx = 160)
        val one = IosPreviewBench.Attribution.end()

        assertTrue(two.containsKey(IosPreviewBench.STAGE_IMAGE_IO), "two decodes must attribute")
        assertTrue(one.containsKey(IosPreviewBench.STAGE_IMAGE_IO), "one decode must attribute")
        assertEquals(
            1,
            one.size,
            "a placeholder-only window must report exactly the decode stage (got $one)",
        )
    }

    private fun writeSource(width: Int, height: Int): String {
        val path = NSTemporaryDirectory().trimEnd('/') +
            "/ewm_attr_src_" + NSUUID().UUIDString() + ".png"
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(Color(0xFF204060))
        }
        val bytes = IosWatermarkRenderer.encodePng(bmp)
        assertTrue(
            IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true),
            "attribution test source must be written",
        )
        return path
    }
}
