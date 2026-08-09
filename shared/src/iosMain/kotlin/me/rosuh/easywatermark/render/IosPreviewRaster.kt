package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.repo.IosIconPersistence

/**
 * **On-screen editor preview** raster — Android [WaterMarkCanvas] analogue for iOS (C3).
 *
 * Unlike [IosExportPipelinePort] / [IosFinalRenderSpine], this path:
 * - never encodes final JPEG/PNG product output
 * - never writes temp export files
 * - decodes + downscales source in one pass to [PREVIEW_MAX_EDGE_PX]
 * - paints through [CommonWatermarkPipeline.compose] with the current offset
 * - returns an in-memory [ImageBitmap] ready for Compose [Image]
 */
/** J5: preview raster — not called from Swift. */
internal object IosPreviewRaster {

    /**
     * Display-sized long edge. Android samples to canvas pixels (~screen); 720 keeps Skiko
     * composition snappy on multi-megapixel camera stills while remaining sharp on phone DPI.
     */
    const val PREVIEW_MAX_EDGE_PX: Int = 720

    /**
     * Fast source placeholder (no watermark) for instant filmstrip feedback while raster runs.
     */
    fun decodeSourcePlaceholder(sourcePath: String, maxEdgePx: Int = PREVIEW_MAX_EDGE_PX): ImageBitmap? {
        val bench = IosPreviewBench.scope("placeholder")
        return try {
            val bmp = decodePathThumbnail(sourcePath, maxEdgePx)
            bench.mark("imageIOThumbnail")
            bench.finish(mapOf("path" to sourcePath.substringAfterLast('/'), "w" to bmp.width, "h" to bmp.height))
            bmp
        } catch (t: Throwable) {
            bench.finish(mapOf("error" to (t.message ?: "fail"), "path" to sourcePath.substringAfterLast('/')))
            null
        }
    }

    /**
     * Watermarked preview [ImageBitmap] for [sourcePath] with current [waterMark] config.
     * In-memory only — no encode/write.
     */
    fun renderWatermarked(
        sourcePath: String,
        waterMark: WaterMark,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        maxEdgePx: Int = PREVIEW_MAX_EDGE_PX,
    ): ImageBitmap {
        val bench = IosPreviewBench.scope("wm_preview")
        val background = decodePathThumbnail(sourcePath, maxEdgePx)
        bench.mark("imageIOThumbnail")

        val icon = if (waterMark.markMode == WatermarkMode.Image) {
            val iconBytes = IosIconPersistence.readIconBytes(waterMark.iconUri)
            IosImageDecoder.decodeThumbnail(iconBytes, maxEdgePx = 256)
        } else {
            null
        }
        // ADR-0025: system default for Text; Image ignores family.
        val family = if (waterMark.markMode == WatermarkMode.Text) {
            FontFamily.Default
        } else {
            null
        }
        val composed = CommonWatermarkPipeline.compose(
            background = background,
            config = waterMark,
            env = IosTextRasterEnv.textRasterEnv(),
            icon = icon,
            offsetX = offsetX,
            offsetY = offsetY,
            fontFamily = family,
        )
        bench.mark("compose")
        bench.finish(
            mapOf(
                "path" to sourcePath.substringAfterLast('/'),
                "w" to composed.width,
                "h" to composed.height,
                "mode" to waterMark.markMode.name,
            ),
        )
        return composed
    }

    /**
     * Picker/source previews are URL-first. ImageIO covers JPEG/PNG/HEIF and applies orientation
     * while requesting the bounded native thumbnail. It intentionally fails closed for an
     * unsupported type: re-reading a full source as NSData/ByteArray would defeat the bounded,
     * file-first import contract. Final export remains a separate full-resolution spine.
     */
    private fun decodePathThumbnail(path: String, maxEdgePx: Int): ImageBitmap =
        IosImageIODecoder.decodeThumbnail(path, maxEdgePx)
}
