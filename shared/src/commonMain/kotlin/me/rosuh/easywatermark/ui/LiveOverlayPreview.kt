package me.rosuh.easywatermark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.OverlayPreviewChrome
import me.rosuh.easywatermark.render.RasteredWatermarkCell
import me.rosuh.easywatermark.render.drawWatermarkTiles
import kotlin.math.min

/**
 * One watermark cell plus REPEAT/CLAMP placement for the editor overlay (ADR-0033).
 * [builtForWidth] is the preview-resolution photo width the cell was rastered for.
 * [logicalWidth] / [logicalHeight] track the un-capped cell pitch when [cell] omits gap margins
 * or is downscaled; [drawWidth] / [drawHeight] track the centered content box represented by [cell].
 */
data class OverlayCell(
    val cell: ImageBitmap,
    val tileMode: WatermarkTileMode,
    val offsetX: Float,
    val offsetY: Float,
    val alpha: Float,
    val builtForWidth: Int,
    val renderedConfig: WaterMark? = null,
    val logicalWidth: Int = cell.width,
    val logicalHeight: Int = cell.height,
    val drawWidth: Int = logicalWidth,
    val drawHeight: Int = logicalHeight,
    val contentWidth: Float = drawWidth.toFloat(),
    val contentHeight: Float = drawHeight.toFloat(),
)

private val EmptyOverlayPixel: ImageBitmap by lazy { ImageBitmap(1, 1) }

/**
 * Empty transparent overlay cell representing intentional "no active sticker" idle state while
 * keeping live photo layers active and razor-sharp (zero fallback to low-res WaitThumb).
 */
fun emptyOverlayCellFor(config: WaterMark, builtForWidth: Int): OverlayCell = OverlayCell(
    cell = EmptyOverlayPixel,
    tileMode = when (config.tileMode) {
        WatermarkTileMode.CLAMP -> WatermarkTileMode.CLAMP
        else -> WatermarkTileMode.REPEAT
    },
    offsetX = 0.5f,
    offsetY = 0.5f,
    alpha = 0f,
    builtForWidth = builtForWidth.coerceAtLeast(1),
    renderedConfig = config,
    logicalWidth = 1,
    logicalHeight = 1,
    drawWidth = 1,
    drawHeight = 1,
    contentWidth = 0f,
    contentHeight = 0f,
)

fun OverlayCell.withOffset(offsetX: Float, offsetY: Float): OverlayCell =
    copy(offsetX = offsetX, offsetY = offsetY)

fun overlayCellFrom(
    cell: ImageBitmap,
    config: WaterMark,
    offsetX: Float,
    offsetY: Float,
    builtForWidth: Int,
): OverlayCell = overlayCellFrom(
    raster = RasteredWatermarkCell(cell),
    config = config,
    offsetX = offsetX,
    offsetY = offsetY,
    builtForWidth = builtForWidth,
)

fun overlayCellFrom(
    raster: RasteredWatermarkCell,
    config: WaterMark,
    offsetX: Float,
    offsetY: Float,
    builtForWidth: Int,
): OverlayCell = OverlayCell(
    cell = raster.bitmap,
    tileMode = when (config.tileMode) {
        WatermarkTileMode.CLAMP -> WatermarkTileMode.CLAMP
        else -> WatermarkTileMode.REPEAT
    },
    offsetX = if (config.tileMode == WatermarkTileMode.REPEAT) config.repeatOffsetX else offsetX,
    offsetY = if (config.tileMode == WatermarkTileMode.REPEAT) config.repeatOffsetY else offsetY,
    alpha = (config.alpha.coerceIn(0, 255)) / 255f,
    builtForWidth = builtForWidth,
    renderedConfig = config,
    logicalWidth = raster.logicalWidth,
    logicalHeight = raster.logicalHeight,
    drawWidth = raster.drawWidth,
    drawHeight = raster.drawHeight,
    contentWidth = raster.contentWidth,
    contentHeight = raster.contentHeight,
)

/**
 * Display size of [overlay] after ContentScale.Fit of [photo] into [boxWidth]×[boxHeight].
 * Used for CLAMP hit-testing (cell box, not the full photo).
 */
fun overlayCellDisplaySize(
    photo: ImageBitmap,
    overlay: OverlayCell,
    boxWidth: Float,
    boxHeight: Float,
): Pair<Float, Float> {
    val dest = fitDestRect(
        srcW = photo.width.toFloat(),
        srcH = photo.height.toFloat(),
        boxW = boxWidth,
        boxH = boxHeight,
    ) ?: return 0f to 0f
    val scale = dest.width / photo.width.coerceAtLeast(1)
    return overlay.logicalWidth * scale to overlay.logicalHeight * scale
}

/**
 * Tile [overlay] in the ContentScale.Fit photo rect. Bake clips via the output bitmap;
 * this Canvas is the full preview pane, so callers must clip to [fitDestRect] or tiles
 * leak into letterbox (device: extra REPEAT row below a landscape photo).
 */
internal fun DrawScope.drawLiveOverlayLayer(
    photoWidth: Int,
    photoHeight: Int,
    overlay: OverlayCell,
) {
    if (overlay.alpha <= 0f) return
    val dest = fitDestRect(
        srcW = photoWidth.toFloat(),
        srcH = photoHeight.toFloat(),
        boxW = size.width,
        boxH = size.height,
    ) ?: return
    clipRect(
        left = dest.left,
        top = dest.top,
        right = dest.left + dest.width,
        bottom = dest.top + dest.height,
    ) {
        val sx = dest.width / photoWidth.coerceAtLeast(1)
        val sy = dest.height / photoHeight.coerceAtLeast(1)
        translate(dest.left, dest.top) {
            scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                drawWatermarkTiles(
                    cell = overlay.cell,
                    tileMode = overlay.tileMode,
                    destWidth = photoWidth.toFloat(),
                    destHeight = photoHeight.toFloat(),
                    offsetX = overlay.offsetX,
                    offsetY = overlay.offsetY,
                    alpha = overlay.alpha,
                    tileWidth = overlay.logicalWidth,
                    tileHeight = overlay.logicalHeight,
                    drawWidth = overlay.drawWidth,
                    drawHeight = overlay.drawHeight,
                )
            }
        }
    }
}

internal fun fitDestRect(
    srcW: Float,
    srcH: Float,
    boxW: Float,
    boxH: Float,
): FittedImageRect? {
    if (srcW <= 0f || srcH <= 0f || boxW <= 0f || boxH <= 0f) return null
    val scale = min(boxW / srcW, boxH / srcH)
    val destW = srcW * scale
    val destH = srcH * scale
    return FittedImageRect(
        left = (boxW - destW) / 2f,
        top = (boxH - destH) / 2f,
        width = destW,
        height = destH,
    )
}

/**
 * Editor main preview: wait chrome (filmstrip thumb / empty) or atomic photo + tiled cell.
 * Hard-cut on path change — no previous photo, no path-change crossfade.
 */
@Composable
fun LiveOverlayPreview(
    chrome: OverlayPreviewChrome,
    photo: ImageBitmap?,
    overlay: OverlayCell?,
    waitThumb: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Watermark preview",
    onOffsetCommit: ((Float, Float) -> Unit)? = null,
    renderGestureCell: (suspend (WaterMark) -> ImageBitmap)? = null,
    renderGestureCellRaster: (suspend (WaterMark) -> RasteredWatermarkCell)? = null,
) {
    val interaction = LocalPreviewInteraction.current
    val effectiveRenderRaster: (suspend (WaterMark) -> RasteredWatermarkCell)? =
        renderGestureCellRaster ?: renderGestureCell?.let { fn ->
            { wm: WaterMark -> RasteredWatermarkCell(fn(wm)) }
        }
    val interactive = interaction != null && onOffsetCommit != null && effectiveRenderRaster != null
    // Wait thumbs and live layers must use the same Fit viewport during photo switches.
    val waitingModifier = if (interactive) modifier.padding(top = PreviewToolbarHeight) else modifier
    when (chrome) {
        OverlayPreviewChrome.EditorEmpty,
        OverlayPreviewChrome.WaitEmpty,
        -> Box(waitingModifier)
        OverlayPreviewChrome.WaitThumb -> {
            Box(waitingModifier) {
                waitThumb?.invoke(Modifier.fillMaxSize())
            }
        }
        OverlayPreviewChrome.LiveLayers -> {
            if (photo == null || overlay == null) {
                Box(waitingModifier)
                return
            }
            if (interaction != null && onOffsetCommit != null && effectiveRenderRaster != null) {
                Box(modifier) {
                    InteractiveOverlayPreview(photo, overlay, interaction, onOffsetCommit, effectiveRenderRaster, contentDescription)
                }
                return
            }
            Box(modifier) {
                Image(
                    bitmap = photo,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    drawLiveOverlayLayer(
                        photoWidth = photo.width,
                        photoHeight = photo.height,
                        overlay = overlay,
                    )
                }
            }
        }
    }
}
