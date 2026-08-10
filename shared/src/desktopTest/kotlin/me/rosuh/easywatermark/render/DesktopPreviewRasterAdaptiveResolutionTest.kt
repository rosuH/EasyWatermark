package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adaptive committed preview: 1440 bucket raster + production maxEdge helper (draft vs committed).
 */
class DesktopPreviewRasterAdaptiveResolutionTest {

    @Test
    fun committedPreview_maxEdge1440_rasterMatchesBucket() {
        val sourceBytes = solidPng(2048, 1536, 0xFF304050.toInt())
        val wm = WaterMark.default.copy(
            markMode = WatermarkMode.Text,
            text = "preview",
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 14f,
            degree = 0f,
            alpha = 255,
        )
        val preview = DesktopPreviewRaster.renderWatermarked(
            imageBytes = sourceBytes,
            waterMark = wm,
            offsetX = 0.5f,
            offsetY = 0.5f,
            maxEdgePx = PreviewResolutionPolicy.BUCKET_1440,
        )
        assertEquals(1440, preview.width)
        assertEquals(1080, preview.height)
    }

    @Test
    fun maxEdgeForPaint_matchesSharedPolicy_draft720_committedBucket() {
        assertEquals(
            720,
            DesktopPreviewRaster.maxEdgeForPaint(isDraft = true, committedBucketPx = 1440),
        )
        assertEquals(
            1440,
            DesktopPreviewRaster.maxEdgeForPaint(isDraft = false, committedBucketPx = 1440),
        )
        assertEquals(
            1920,
            DesktopPreviewRaster.maxEdgeForPaint(isDraft = false, committedBucketPx = 1920),
        )
        assertEquals(1440, DesktopPreviewRaster.committedMaxEdgePx(1206, 800))
    }

    @Test
    fun defaultMaxEdge_remains720_forPlaceholderTier() {
        assertEquals(720, DesktopPreviewRaster.PREVIEW_MAX_EDGE_PX)
        val sourceBytes = solidPng(2048, 1536, 0xFF203040.toInt())
        val wm = WaterMark.default.copy(markMode = WatermarkMode.Text, text = "d")
        val preview = DesktopPreviewRaster.renderWatermarked(
            imageBytes = sourceBytes,
            waterMark = wm,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        assertTrue(preview.width <= 720)
        assertTrue(preview.height <= 720)
        assertEquals(720, maxOf(preview.width, preview.height))
    }

    private fun solidPng(w: Int, h: Int, rgb: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(rgb, true)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }
}
