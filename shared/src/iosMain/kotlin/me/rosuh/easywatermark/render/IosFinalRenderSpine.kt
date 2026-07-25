package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Color as SkiaColor
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * Immutable Final Export request (C3). All fields required — no center-offset defaults.
 * Freezes config, prefs, and per-item offset before source/file IO. Does not own paths or Session.
 */
data class IosRenderRequest(
    val config: WaterMark,
    val prefs: UserPreferences,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * Internal Final Export result: encoded bytes + actual format + upright dimensions.
 * Not a Session/Port public result type (Stage D may promote later).
 */
data class IosEncodedImage(
    val bytes: ByteArray,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val byteCount: Int,
) {
    init {
        require(byteCount == bytes.size) {
            "IosEncodedImage.byteCount ($byteCount) must equal bytes.size (${bytes.size})"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is IosEncodedImage &&
                format == other.format &&
                width == other.width &&
                height == other.height &&
                byteCount == other.byteCount &&
                bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int =
        ((format.hashCode() * 31 + width) * 31 + height) * 31 + bytes.contentHashCode()
}

/**
 * iOS Final Export spine (C3): full-resolution decode → [CommonWatermarkPipeline.compose] →
 * explicit-sRGB **working surface** encode (JPEG white-flatten + quality; PNG alpha preserve).
 *
 * Product sRGB contract is the encode working surface ([explicitSrgbImageInfo] / [ImageInfo.makeS32]),
 * not a guarantee that decoded JPEG/PNG containers re-report a non-null sRGB profile.
 *
 * Production Text passes [IosFontLoader.bundledFontFamily]; Image does not load fonts.
 * Preview remains [IosPreviewRaster] (720 budget, in-memory, no encode).
 */
/** J5: export pipeline implementation — not called from Swift. */
internal object IosFinalRenderSpine {

    /**
     * Explicit sRGB S32 working [ImageInfo] used before encode. Color space must be non-null sRGB.
     */
    fun explicitSrgbImageInfo(width: Int, height: Int, alphaType: ColorAlphaType): ImageInfo {
        val info = ImageInfo.makeS32(width.coerceAtLeast(1), height.coerceAtLeast(1), alphaType)
        require(info.colorSpace == ColorSpace.sRGB) {
            "IosFinalRenderSpine: explicit sRGB working surface required (got ${info.colorSpace})"
        }
        return info
    }

    /**
     * Full-resolution decode + common compose only (no encode). Used by [renderAndEncode] and the
     * compatibility bridge so RENDER vs ENCODE failures stay structurally separate.
     */
    fun composeForExport(
        imageBytes: ByteArray,
        request: IosRenderRequest,
        iconBytes: ByteArray? = null,
        fontFamily: FontFamily? = null,
    ): ImageBitmap {
        val background = IosImageDecoder.decode(imageBytes)
        val icon = if (request.config.markMode == WatermarkMode.Image) {
            require(iconBytes != null && iconBytes.isNotEmpty()) {
                "Image-mode Final Export requires non-empty iconBytes"
            }
            IosImageDecoder.decode(iconBytes)
        } else {
            null
        }
        val familyForText = if (request.config.markMode == WatermarkMode.Text) fontFamily else null
        return CommonWatermarkPipeline.compose(
            background = background,
            config = request.config,
            env = IosTextRasterEnv.textRasterEnv(),
            icon = icon,
            offsetX = request.offsetX,
            offsetY = request.offsetY,
            fontFamily = familyForText,
        )
    }

    /**
     * Full-resolution product compose+encode.
     *
     * @param fontFamily optional injectable family for tests; production Text should pass bundled CJK.
     *   Image mode ignores fonts (pipeline Image path).
     */
    fun renderAndEncode(
        imageBytes: ByteArray,
        request: IosRenderRequest,
        iconBytes: ByteArray? = null,
        fontFamily: FontFamily? = null,
    ): IosEncodedImage {
        val composed = composeForExport(
            imageBytes = imageBytes,
            request = request,
            iconBytes = iconBytes,
            fontFamily = fontFamily,
        )
        val format = request.prefs.outputFormat
        val quality = request.prefs.compressLevel.coerceIn(0, 100)
        val encoded = encodeExplicitSrgb(composed, format, quality)
        return IosEncodedImage(
            bytes = encoded,
            format = format,
            width = composed.width,
            height = composed.height,
            byteCount = encoded.size,
        )
    }

    /**
     * Draw [composed] into a new Skia S32 sRGB working surface, then encode.
     * JPEG: opaque white fill then draw (alpha flatten). PNG: premul alpha preserved.
     */
    fun encodeExplicitSrgb(
        composed: ImageBitmap,
        format: ImageFormat,
        quality: Int,
    ): ByteArray {
        val w = composed.width.coerceAtLeast(1)
        val h = composed.height.coerceAtLeast(1)
        val alphaType = when (format) {
            ImageFormat.JPEG -> ColorAlphaType.OPAQUE
            ImageFormat.PNG -> ColorAlphaType.PREMUL
        }
        val info = explicitSrgbImageInfo(w, h, alphaType)
        val surface = Surface.makeRaster(info)
            ?: error("IosFinalRenderSpine: Surface.makeRaster failed (${w}x$h)")
        val canvas = surface.canvas
        if (format == ImageFormat.JPEG) {
            canvas.clear(SkiaColor.WHITE)
        } else {
            canvas.clear(SkiaColor.TRANSPARENT)
        }
        val srcImage = SkiaImage.makeFromBitmap(composed.asSkiaBitmap())
        canvas.drawImage(srcImage, 0f, 0f)
        val snapshot = surface.makeImageSnapshot()
            ?: error("IosFinalRenderSpine: makeImageSnapshot returned null")
        val encFormat = when (format) {
            ImageFormat.JPEG -> EncodedImageFormat.JPEG
            ImageFormat.PNG -> EncodedImageFormat.PNG
        }
        val q = when (format) {
            ImageFormat.JPEG -> quality.coerceIn(0, 100)
            ImageFormat.PNG -> 100
        }
        val data = snapshot.encodeToData(encFormat, q)
            ?: error("IosFinalRenderSpine: Skia encodeToData returned null for $format")
        return data.bytes
    }
}
