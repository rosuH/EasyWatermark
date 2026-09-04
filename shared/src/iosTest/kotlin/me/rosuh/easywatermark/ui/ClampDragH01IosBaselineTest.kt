@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosPreviewRaster
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * H0.1 iOS **measurement** of post-commit watermarked preview raster
 * ([IosPreviewRaster.renderWatermarked]) — the work [IosProductRootHost] runs after CLAMP
 * applyOffset + cache eviction (no full encode/temp write, unlike Desktop).
 *
 * Uses **Image** mode (same as [me.rosuh.easywatermark.render.IosPreviewRasterTest]) so the
 * iosSimulatorArm64 test bundle does not need packaged Noto fonts.
 *
 * No invented SLOs. Live draft during drag remains false (adapter contract).
 */
class ClampDragH01IosBaselineTest {

    @BeforeTest
    fun enable() {
        ClampDragBench.enabled = true
        ClampDragBench.resetForTests()
    }

    @AfterTest
    fun silence() {
        ClampDragBench.enabled = false
    }

    private fun solidPng(w: Int, h: Int, color: Color): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return IosWatermarkRenderer.encodePng(bmp)
    }

    @Test
    fun ios_postCommitWmPreview_stageTimings_clampFixture() {
        val dir = NSTemporaryDirectory()
        val path = dir + "h01_" + NSUUID().UUIDString + ".png"
        // ~2MP-ish still for a realistic preview decode; max edge 720 inside raster.
        val png = solidPng(1600, 1200, Color(0xFF304050))
        assertTrue(IosByteArrayInterop.toNSData(png).writeToFile(path, atomically = true))

        val iconPath = dir + "h01_icon_" + NSUUID().UUIDString + ".png"
        val iconBytes = solidPng(48, 36, Color.Red)
        assertTrue(IosByteArrayInterop.toNSData(iconBytes).writeToFile(iconPath, atomically = true))

        // Image mode avoids Noto font packaging requirement in the test executable bundle.
        val wm = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 14f,
            degree = 0f,
            alpha = 255,
        )

        // Warm once.
        IosPreviewRaster.renderWatermarked(path, wm, 0.5f, 0.5f)

        fun measure(ox: Float, oy: Float): Long {
            val bench = ClampDragBench.previewScope("ios_preview_refresh")
            val t0 = TimeSource.Monotonic.markNow()
            bench.mark("sessionRead") // host always reads session first
            val composed = IosPreviewRaster.renderWatermarked(path, wm, ox, oy)
            bench.mark("raster")
            bench.mark("cachePut")
            val total = t0.elapsedNow().inWholeMilliseconds
            bench.finish(
                mapOf(
                    "hit" to false,
                    "w" to composed.width,
                    "h" to composed.height,
                    "offsetX" to ox,
                    "offsetY" to oy,
                    "maxEdge" to IosPreviewRaster.PREVIEW_MAX_EDGE_PX,
                    "mode" to "Image",
                    "liveDraft" to false,
                    "encodeTempWrite" to false,
                ),
            )
            return total
        }

        val centerMs = measure(0.5f, 0.5f)
        val movedMs = measure(0.2f, 0.8f)

        println(
            "H0.1 iOS wm_preview fixture 1600x1200→maxEdge720 CLAMP Image " +
                "centerMs=$centerMs movedMs=$movedMs last=${ClampDragBench.lastLine}",
        )

        assertTrue(movedMs >= 0)
        assertTrue(centerMs >= 0)
        val line = assertNotNull(ClampDragBench.lastLine)
        assertTrue(line.contains("ios_preview_refresh"), line)
        // IosPreviewBench still prints read/decodeScale/compose inside renderWatermarked.
    }
}
