package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * The **iOS watermark renderer** — the iOS analogue of [DesktopWatermarkTextRenderer] + * [DesktopWatermarkComposer], proving the accepted commonMain pipeline runs on the iOS (Skiko) target:
 * render a text cell ([WatermarkCellComposer.composeTextCell]) → compose it over a decoded image
 * ([WatermarkCellComposer.composeOverBackground]) → (optionally) Skia-encode to PNG.
 *
 * iOS, like desktop, is a Skiko backend, so the only platform pieces are the env/font boundary
 * ([IosTextRasterEnv]), the decode boundary ([IosImageDecoder]), and Skia PNG encode here. commonMain stays
 * decode-free and platform-neutral. Android production also uses commonMain via `AndroidCommonRaster`
 * (ADR-0018); native `WatermarkRenderer` is oracle/golden only.
 *
 * The [fontFamily] is injected (defaults to [FontFamily.Default] = the iOS system font via Skiko) so the
 * pipeline can be proven without packaging the bundled CJK font into an iOS app bundle yet; pass
 * [IosTextRasterEnv.bundledFontFamily] with real Noto bytes for the bundled-font path (see C5).
 */
object IosWatermarkRenderer {

    /** Render ONE watermark text cell via the shared [WatermarkCellComposer.composeTextCell] on iOS. */
    fun renderTextCell(
        text: String,
        fontFamily: FontFamily = FontFamily.Default,
        textSize: Float = 24f,
        imageWidth: Int = WatermarkGeometry.REF_WIDTH.toInt(),
        degree: Float = 0f,
        color: Color = Color.White,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        // persisted text typeface; default Normal preserves the prior (regular) output.
        typeface: TextTypeface = TextTypeface.Normal,
        // persisted text paint style; default Fill preserves the prior (filled) output.
        textStyle: TextPaintStyle = TextPaintStyle.Fill,
    ): ImageBitmap {
        val fontPx = WatermarkGeometry.fontPx(textSize, imageWidth)
        val (fontWeight, fontStyle) = typeface.toComposeFontStyle()
        val content = WatermarkTextContent(
            text = text,
            style = TextStyle(
                fontSize = fontPx.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontStyle = fontStyle,
                drawStyle = textStyle.toComposeDrawStyle(),
            ),
            color = color,
        )
        return WatermarkCellComposer.composeTextCell(
            env = IosTextRasterEnv.textRasterEnv(),
            content = content,
            degree = degree,
            hGapPercent = hGapPercent,
            vGapPercent = vGapPercent,
        )
    }

    /**
 * Render ONE watermark **icon** cell via the shared [WatermarkCellComposer.composeIconCell] * on iOS — the icon analogue of [renderTextCell], and the iOS/Skiko icon renderer (the accepted
 * Desktop/iOS icon path per / the ADR-0004 addendum). Takes an **already-decoded** [icon]; image
 * **decode stays the [IosImageDecoder] boundary** and commonMain stays decode-free.
 *
 * **Perceptual, NOT byte-parity** with native `WatermarkRenderer.buildIconShader`: commonMain has no
 * float-placement + nearest-filter draw overload, so rotated non-uniform icons are not
 * byte-identical to native `Canvas.drawBitmap`. Production Android/Desktop/iOS share this common
 * icon path (ADR-0018); native remains dual-path/golden only.
 *
 * @param scaleRatio icon scale; production passes
 * `WaterMark.textSize / WatermarkCellComposer.ICON_SCALE_REFERENCE_TEXT_SIZE` (14f ⇒ 1×)
 * @param alpha icon opacity baked into the cell (0f..1f, default opaque). NOTE:
 * [composeIconOverImage] leaves this at the default and instead applies the watermark
 * alpha ONCE at the composition step (see its KDoc), mirroring [composeOverImage].
     */
    fun renderIconCell(
        icon: ImageBitmap,
        degree: Float = 0f,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        scaleRatio: Float = 1f,
        alpha: Float = 1f,
    ): ImageBitmap = WatermarkCellComposer.composeIconCell(
        icon = icon,
        degree = degree,
        hGapPercent = hGapPercent,
        vGapPercent = vGapPercent,
        scaleRatio = scaleRatio,
        alpha = alpha,
    )

    /** Encode an [ImageBitmap] to PNG bytes via Skia (the iOS analogue of the desktop AWT encode). */
    fun encodePng(bitmap: ImageBitmap): ByteArray {
        val data = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            ?: error("IosWatermarkRenderer: Skia PNG encode returned null")
        return data.bytes
    }

    /**
 * Full iOS pipeline: **decode** [imageBytes] ([IosImageDecoder]) → **render** a text cell → **compose**
 * Over the decoded image ([WatermarkCellComposer.composeOverBackground]) → return the composed * [ImageBitmap] (sized to the decoded image). [tileMode] must be REPEAT or CLAMP (commonMain rejects
 * MIRROR/DECAL).
     */
    fun composeOverImage(
        imageBytes: ByteArray,
        text: String,
        fontFamily: FontFamily = FontFamily.Default,
        tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
        textSize: Float = 24f,
        degree: Float = 315f,
        hGapPercent: Int = 40,
        vGapPercent: Int = 40,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        alpha: Float = 1f,
        // ARGB text color (default amber #FFB800), converted to a Compose Color below. Replaces
        // the prior hardcoded white so the iOS render honors the shared WaterMark.textColor default.
        colorArgb: Int = WaterMark.default.textColor,
        // persisted text typeface; default Normal preserves the prior (regular) output.
        typeface: TextTypeface = TextTypeface.Normal,
        // persisted text paint style; default Fill preserves the prior (filled) output.
        textStyle: TextPaintStyle = TextPaintStyle.Fill,
    ): ImageBitmap {
        val background = IosImageDecoder.decode(imageBytes)
        val cell = renderTextCell(
            text = text,
            fontFamily = fontFamily,
            textSize = textSize,
            imageWidth = background.width,
            degree = degree,
            color = Color(colorArgb),
            hGapPercent = hGapPercent,
            vGapPercent = vGapPercent,
            typeface = typeface,
            textStyle = textStyle,
        )
        return WatermarkCellComposer.composeOverBackground(
            background = background,
            cell = cell,
            tileMode = tileMode,
            offsetX = offsetX,
            offsetY = offsetY,
            alpha = alpha,
        )
    }

    /**
 * Full iOS **icon** pipeline — the icon analogue of [composeOverImage]: **decode** * [imageBytes] + [iconBytes] ([IosImageDecoder]) → **render** the icon cell ([renderIconCell] →
 * [WatermarkCellComposer.composeIconCell]) → **compose** over the decoded background
 * ([WatermarkCellComposer.composeOverBackground]) → composed [ImageBitmap] sized to the background.
 * [tileMode] must be REPEAT or CLAMP (commonMain rejects MIRROR/DECAL). commonMain stays decode-free;
 * decode is the [IosImageDecoder] boundary for both the background and the icon.
 *
 * **Alpha is applied ONCE, at the composition step** (the icon cell is rendered opaque), exactly as
 * [composeOverImage] applies text alpha. (Android bakes alpha into the icon cell in `buildIconShader`
 * **and** re-applies it via the shared paint in `compose`, i.e. double-applies; iOS applies it once —
 * single application is the visually-correct behavior, and iOS icon rendering is perceptual, not
 * byte-parity with Android.)
 *
 * @param scaleRatio production passes
 * `WaterMark.textSize / WatermarkCellComposer.ICON_SCALE_REFERENCE_TEXT_SIZE` (14f ⇒ 1×)
 * @param alpha normalized watermark opacity 0f..1f, applied at composition
     */
    fun composeIconOverImage(
        imageBytes: ByteArray,
        iconBytes: ByteArray,
        tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
        degree: Float = 315f,
        hGapPercent: Int = 40,
        vGapPercent: Int = 40,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        scaleRatio: Float = 1f,
        alpha: Float = 1f,
    ): ImageBitmap {
        val background = IosImageDecoder.decode(imageBytes)
        val icon = IosImageDecoder.decode(iconBytes)
        val cell = renderIconCell(
            icon = icon,
            degree = degree,
            hGapPercent = hGapPercent,
            vGapPercent = vGapPercent,
            scaleRatio = scaleRatio,
            // Opaque cell; the watermark alpha is applied once at the composition step below
            // (mirrors composeOverImage for text — see this function's KDoc).
            alpha = 1f,
        )
        return WatermarkCellComposer.composeOverBackground(
            background = background,
            cell = cell,
            tileMode = tileMode,
            offsetX = offsetX,
            offsetY = offsetY,
            alpha = alpha,
        )
    }
}
