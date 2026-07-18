package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode

/**
 * Pure commonMain paint path for preview and export (ADR-0018).
 *
 * Platforms supply decoded background (and optional icon), [TextRasterEnv], and [WaterMark].
 * Encode / MediaStore / Photos stay platform-side.
 */
object CommonWatermarkPipeline {

    /**
 * Compose [config] over [background] using shared cell + [WatermarkCellComposer.composeOverBackground].
 *
 * @param icon required when [WaterMark.markMode] is [WatermarkMode.Image]; ignored for Text mode.
     */
    fun compose(
        background: ImageBitmap,
        config: WaterMark,
        env: TextRasterEnv,
        icon: ImageBitmap? = null,
    ): ImageBitmap {
        return compose(
            background = background,
            config = config,
            env = env,
            icon = icon,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
    }

    /**
 * Same as [compose] but applies fractional [offsetX]/[offsetY] for CLAMP/decal placement
 * (image-space 0..1, matching Android [ImageInfo.offsetX]/[offsetY]).
     */
    fun compose(
        background: ImageBitmap,
        config: WaterMark,
        env: TextRasterEnv,
        icon: ImageBitmap?,
        offsetX: Float,
        offsetY: Float,
    ): ImageBitmap {
        val cell = when (config.markMode) {
            WatermarkMode.Text -> composeTextCell(background.width, config, env)
            WatermarkMode.Image -> {
                require(icon != null && icon.width > 0 && icon.height > 0) {
                    "CommonWatermarkPipeline: Image mode requires a decoded non-empty icon"
                }
                composeIconCell(config, icon)
            }
        }
        val tileMode = when (config.tileMode) {
            WatermarkTileMode.CLAMP -> WatermarkTileMode.CLAMP
            else -> WatermarkTileMode.REPEAT
        }
        return WatermarkCellComposer.composeOverBackground(
            background = background,
            cell = cell,
            tileMode = tileMode,
            offsetX = offsetX,
            offsetY = offsetY,
            alpha = (config.alpha.coerceIn(0, 255)) / 255f,
        )
    }

    fun composeTextCell(
        imageWidth: Int,
        config: WaterMark,
        env: TextRasterEnv,
    ): ImageBitmap {
        val text = config.text.ifEmpty { " " }
        val fontPx = WatermarkGeometry.fontPx(config.textSize, imageWidth)
        val (weight, fontStyle) = config.textTypeface.toComposeFontStyle()
        val content = WatermarkTextContent(
            text = text,
            style = TextStyle(
                fontWeight = weight,
                fontStyle = fontStyle,
                fontSize = fontPx.sp,
                drawStyle = config.textStyle.toComposeDrawStyle(),
            ),
            color = Color(config.textColor),
        )
        return WatermarkCellComposer.composeTextCell(
            env = env,
            content = content,
            degree = config.degree,
            hGapPercent = config.hGap,
            vGapPercent = config.vGap,
        )
    }

    fun composeIconCell(config: WaterMark, icon: ImageBitmap): ImageBitmap =
        WatermarkCellComposer.composeIconCell(
            icon = icon,
            degree = config.degree,
            hGapPercent = config.hGap,
            vGapPercent = config.vGap,
            scaleRatio = config.textSize / WatermarkCellComposer.ICON_SCALE_REFERENCE_TEXT_SIZE,
            alpha = 1f, // alpha applied once at composition (iOS rule)
        )
}
