package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import java.io.File

/**
 * Desktop **on-screen editor preview** raster (H0.1-fix) — iOS [IosPreviewRaster] analogue.
 *
 * ## Resolution policy (see [PreviewResolutionPolicy])
 *
 * - **Draft** (CLAMP drag): long edge [PREVIEW_MAX_EDGE_PX] = 720 — latency.
 * - **Committed**: display-driven bucket from the measured preview pane (Fit + density px),
 *   via [committedMaxEdgePx] / [committedMaxEdgePxForFit] — sharpness for large dual-pane.
 * - **Export / Save As**: full-res [DesktopRenderSaveSpine] — never this path.
 *
 * Decode downscales only when source long edge **exceeds** [maxEdgePx]; small sources stay native.
 *
 * Unlike Save As:
 * - never encodes product JPEG/PNG for disk
 * - never writes temp export files
 * - paints through [CommonWatermarkPipeline.compose] with the given offset
 * - returns an in-memory [ImageBitmap] for Compose [Image]
 */
object DesktopPreviewRaster {

    /** Default / transient long edge (active CLAMP draft). Committed paints pass explicit maxEdge. */
    const val PREVIEW_MAX_EDGE_PX: Int = PreviewResolutionPolicy.PLACEHOLDER_MAX_EDGE_PX

    /**
     * Map measured preview-box width/height (**layout px**, density-applied) to a committed bucket.
     * Same policy as iOS ([PreviewResolutionPolicy]).
     */
    fun committedMaxEdgePx(previewBoxWidthPx: Int, previewBoxHeightPx: Int): Int =
        PreviewResolutionPolicy.committedMaxEdgePx(previewBoxWidthPx, previewBoxHeightPx)

    /**
     * Preferred committed bucket when source dims are known (import probe / ImageInfo).
     * Matches iOS [IosProductRootHost] Fit path so large Desktop panes request enough pixels.
     */
    fun committedMaxEdgePxForFit(
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        containerWidthPx: Int,
        containerHeightPx: Int,
    ): Int = PreviewResolutionPolicy.committedMaxEdgePxForFit(
        sourceWidthPx = sourceWidthPx,
        sourceHeightPx = sourceHeightPx,
        containerWidthPx = containerWidthPx,
        containerHeightPx = containerHeightPx,
    )

    /**
     * Production helper: max edge for one light-preview paint.
     * Drafts stay at 720; committed uses the active display bucket.
     */
    fun maxEdgeForPaint(isDraft: Boolean, committedBucketPx: Int): Int =
        PreviewResolutionPolicy.maxEdgeForPaint(isDraft, committedBucketPx)

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
