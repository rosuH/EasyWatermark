package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * **On-screen editor preview** raster — Android [WaterMarkCanvas] analogue for iOS.
 *
 * Unlike [IosExportPipelinePort] / [IosWatermarkRenderBridge.renderWatermarkedPng], this path:
 * - never encodes PNG
 * - never writes temp export files
 * - decodes + downscales source in one pass to [maxEdgePx]
 * - returns an in-memory [ImageBitmap] ready for Compose [Image]
 *
 * That removes the old switch-image cost stack: full-res read → PNG re-encode of downsample →
 * Full export write → PNG read → PNG decode for display. */
object IosPreviewRaster {

    /**
 * Display-sized long edge. Android samples to canvas pixels (~screen); 720 keeps Skiko
 * Composition snappy on multi-megapixel camera stills while remaining sharp on phone DPI.     */
    const val PREVIEW_MAX_EDGE_PX: Int = 720

    private val fontFamily: FontFamily by lazy {
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

        // One-pass decode+scale — no intermediate PNG re-encode (export path used to do that).
        val background = IosImageDecoder.decodeThumbnail(bytes, maxEdgePx = maxEdgePx)
        bench.mark("decodeScale")

        val composed = when (waterMark.markMode) {
            WatermarkMode.Text -> {
                val cell = IosWatermarkRenderer.renderTextCell(
                    text = waterMark.text,
                    fontFamily = fontFamily,
                    textSize = waterMark.textSize,
                    imageWidth = background.width,
                    degree = waterMark.degree,
                    color = Color(waterMark.textColor),
                    hGapPercent = waterMark.hGap,
                    vGapPercent = waterMark.vGap,
                    typeface = waterMark.textTypeface,
                    textStyle = waterMark.textStyle,
                )
                bench.mark("cell")
                WatermarkCellComposer.composeOverBackground(
                    background = background,
                    cell = cell,
                    tileMode = waterMark.tileMode,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    alpha = waterMark.alpha / 255f,
                )
            }
            WatermarkMode.Image -> {
                val iconBytes = IosIconPersistence.readIconBytes(waterMark.iconUri)
                val icon = IosImageDecoder.decodeThumbnail(iconBytes, maxEdgePx = 256)
                val cell = IosWatermarkRenderer.renderIconCell(
                    icon = icon,
                    degree = waterMark.degree,
                    hGapPercent = waterMark.hGap,
                    vGapPercent = waterMark.vGap,
                    scaleRatio = waterMark.textSize / WatermarkCellComposer.ICON_SCALE_REFERENCE_TEXT_SIZE,
                    alpha = 1f,
                )
                bench.mark("cell")
                WatermarkCellComposer.composeOverBackground(
                    background = background,
                    cell = cell,
                    tileMode = waterMark.tileMode,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    alpha = waterMark.alpha / 255f,
                )
            }
        }
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
