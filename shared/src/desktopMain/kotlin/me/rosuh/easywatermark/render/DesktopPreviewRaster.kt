package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import java.io.File

/**
 * Desktop **on-screen editor preview** raster (H0.1-fix) — iOS [IosPreviewRaster] analogue.
 *
 * Unlike [DesktopRenderSaveSpine] / Save As:
 * - never encodes product JPEG/PNG for disk
 * - never writes temp export files
 * - decodes + downscales source to [PREVIEW_MAX_EDGE_PX]
 * - paints through [CommonWatermarkPipeline.compose] with the given offset
 * - returns an in-memory [ImageBitmap] for Compose [Image]
 *
 * Export / Save As continue to use full-res [DesktopRenderSaveSpine] with **committed** Session
 * offsets only — this path is preview-only and may use UI draft offsets.
 */
object DesktopPreviewRaster {

    /** Display-sized long edge (match iOS preview spirit). */
    const val PREVIEW_MAX_EDGE_PX: Int = 720

    /**
     * Watermarked preview [ImageBitmap] for encoded [imageBytes] at [offsetX]/[offsetY].
     * [iconBytes] required when [waterMark] is Image mode.
     */
    fun renderWatermarked(
        imageBytes: ByteArray,
        waterMark: WaterMark,
        offsetX: Float,
        offsetY: Float,
        iconBytes: ByteArray? = null,
        maxEdgePx: Int = PREVIEW_MAX_EDGE_PX,
    ): ImageBitmap {
        val background = DesktopImageDecoder.decodeThumbnail(imageBytes, maxEdgePx = maxEdgePx)
        val icon = if (waterMark.markMode == WatermarkMode.Image) {
            require(iconBytes != null && iconBytes.isNotEmpty()) {
                "Image-mode DesktopPreviewRaster requires non-empty iconBytes"
            }
            DesktopImageDecoder.decodeThumbnail(iconBytes, maxEdgePx = 256)
        } else {
            null
        }
        return CommonWatermarkPipeline.compose(
            background = background,
            config = waterMark,
            env = DesktopWatermarkTextRenderer.textRasterEnv(),
            icon = icon,
            offsetX = offsetX,
            offsetY = offsetY,
            fontFamily = FontFamily.Default,
        )
    }

    /** Convenience: read [sourcePath] then [renderWatermarked]. */
    fun renderWatermarkedFile(
        sourcePath: String,
        waterMark: WaterMark,
        offsetX: Float,
        offsetY: Float,
        iconPath: String? = null,
        maxEdgePx: Int = PREVIEW_MAX_EDGE_PX,
    ): ImageBitmap {
        val file = File(sourcePath)
        require(file.isFile) { "DesktopPreviewRaster: missing source $sourcePath" }
        val imageBytes = file.readBytes()
        val iconBytes = iconPath?.takeIf { it.isNotBlank() }?.let { path ->
            val iconFile = File(path)
            require(iconFile.isFile) { "DesktopPreviewRaster: missing icon $path" }
            iconFile.readBytes()
        }
        return renderWatermarked(
            imageBytes = imageBytes,
            waterMark = waterMark,
            offsetX = offsetX,
            offsetY = offsetY,
            iconBytes = iconBytes,
            maxEdgePx = maxEdgePx,
        )
    }
}
