package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

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

    private val fontFamily by lazy {
        IosFontLoader.bundledFontFamily(latinFirst = true)
    }

    /**
     * Fast source placeholder (no watermark) for instant filmstrip feedback while raster runs.
     */
    fun decodeSourcePlaceholder(sourcePath: String, maxEdgePx: Int = PREVIEW_MAX_EDGE_PX): ImageBitmap? {
        val bench = IosPreviewBench.scope("placeholder")
        return try {
            val bytes = readFileBytes(sourcePath) ?: return null
            bench.mark("read")
            val bmp = IosImageDecoder.decodeThumbnail(bytes, maxEdgePx = maxEdgePx)
            bench.mark("decodeScale")
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
        val bytes = readFileBytes(sourcePath)
            ?: error("IosPreviewRaster: unreadable $sourcePath")
        bench.mark("read")

        val background = IosImageDecoder.decodeThumbnail(bytes, maxEdgePx = maxEdgePx)
        bench.mark("decodeScale")

        val icon = if (waterMark.markMode == WatermarkMode.Image) {
            val iconBytes = IosIconPersistence.readIconBytes(waterMark.iconUri)
            IosImageDecoder.decodeThumbnail(iconBytes, maxEdgePx = 256)
        } else {
            null
        }
        val family = if (waterMark.markMode == WatermarkMode.Text) fontFamily else null
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

    private fun readFileBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return IosByteArrayInterop.fromNSData(data)
    }
}
