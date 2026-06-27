package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * S4d-20B: the **iOS watermark renderer** — the iOS analogue of [DesktopWatermarkTextRenderer] +
 * [DesktopWatermarkComposer], proving the accepted commonMain pipeline runs on the iOS (Skiko) target:
 * render a text cell ([WatermarkCellComposer.composeTextCell]) → compose it over a decoded image
 * ([WatermarkCellComposer.composeOverBackground]) → (optionally) Skia-encode to PNG.
 *
 * iOS, like desktop, is a Skiko backend, so the only platform pieces are the env/font boundary
 * ([IosTextRasterEnv]), the decode boundary ([IosImageDecoder]), and Skia PNG encode here. commonMain stays
 * decode-free and platform-neutral. Android production is untouched (S4d-17 Option C / S4d-8 Option A).
 *
 * The [fontFamily] is injected (defaults to [FontFamily.Default] = the iOS system font via Skiko) so the
 * pipeline can be proven without packaging the bundled CJK font into an iOS app bundle yet; pass
 * [IosTextRasterEnv.bundledFontFamily] with real Noto bytes for the bundled-font path (see C5).
 */
object IosWatermarkRenderer {

    /** Mirrors `WatermarkRenderer.REF_WIDTH` / the desktop renderer: image-space text sizing reference. */
    const val REF_WIDTH: Int = 1000

    /**
     * S4d-112: map the platform-neutral [TextTypeface] to Compose `(fontWeight, fontStyle)`. Bold/italic
     * are **synthetic** (faux-bold emboldening / faux-italic skew) when the bundled font has no matching
     * face, which mirrors Android's `Typeface.create(base, NORMAL/ITALIC/BOLD/BOLD_ITALIC)` synthesis from
     * the regular base. This is **perceptual, not byte-parity** with Android's `StaticLayout` raster
     * (consistent with the iOS-text-is-Skiko policy, S4d-17 Option C).
     */
    private fun TextTypeface.toCompose(): Pair<FontWeight, FontStyle> = when (this) {
        TextTypeface.Normal -> FontWeight.Normal to FontStyle.Normal
        TextTypeface.Italic -> FontWeight.Normal to FontStyle.Italic
        TextTypeface.Bold -> FontWeight.Bold to FontStyle.Normal
        TextTypeface.BoldItalic -> FontWeight.Bold to FontStyle.Italic
    }

    /** Render ONE watermark text cell via the shared [WatermarkCellComposer.composeTextCell] on iOS. */
    fun renderTextCell(
        text: String,
        fontFamily: FontFamily = FontFamily.Default,
        textSize: Float = 24f,
        imageWidth: Int = REF_WIDTH,
        degree: Float = 0f,
        color: Color = Color.White,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        // S4d-112: persisted text typeface; default Normal preserves the prior (regular) output.
        typeface: TextTypeface = TextTypeface.Normal,
    ): ImageBitmap {
        val fontPx = textSize * imageWidth / REF_WIDTH
        val (fontWeight, fontStyle) = typeface.toCompose()
        val content = WatermarkTextContent(
            text = text,
            style = TextStyle(
                fontSize = fontPx.sp,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontStyle = fontStyle,
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

    /** Encode an [ImageBitmap] to PNG bytes via Skia (the iOS analogue of the desktop AWT encode). */
    fun encodePng(bitmap: ImageBitmap): ByteArray {
        val data = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            ?: error("IosWatermarkRenderer: Skia PNG encode returned null")
        return data.bytes
    }

    /**
     * Full iOS pipeline: **decode** [imageBytes] ([IosImageDecoder]) → **render** a text cell → **compose**
     * over the decoded image ([WatermarkCellComposer.composeOverBackground]) → return the composed
     * [ImageBitmap] (sized to the decoded image). [tileMode] must be REPEAT or CLAMP (commonMain rejects
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
        // S4d-107: ARGB text color (default amber #FFB800), converted to a Compose Color below. Replaces
        // the prior hardcoded white so the iOS render honors the shared WaterMark.textColor default.
        colorArgb: Int = WaterMark.default.textColor,
        // S4d-112: persisted text typeface; default Normal preserves the prior (regular) output.
        typeface: TextTypeface = TextTypeface.Normal,
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
