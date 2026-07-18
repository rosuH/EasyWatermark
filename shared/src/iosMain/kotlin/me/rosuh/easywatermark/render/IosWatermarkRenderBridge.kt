package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import platform.Foundation.NSBundle

/**
 * The **iOS Swift-catchable render boundary**. *
 * The iOS render path is a sequence of Kotlin/Native calls that fail **loudly** on bad input —
 * [IosFontLoader.bundledFontFamily] (`error`/`check` → [IllegalStateException] on a missing/unreadable/
 * empty bundled font), [IosWatermarkRenderer.composeOverImage] (which decodes via [IosImageDecoder] —
 * `error` → [IllegalStateException] on undecodable bytes — and validates the tile mode in commonMain
 * `composeOverBackground` — `require` → [IllegalArgumentException] for MIRROR/DECAL), and
 * [IosWatermarkRenderer.encodePng] (`error` → [IllegalStateException] if Skia returns null).
 *
 * Without an annotation, a Kotlin exception crossing the Kotlin/Native ↔ Swift boundary **terminates
 * the process** rather than becoming a Swift `catch`. Once the iOS app actually runs (C5.3), a user
 * picking a corrupt/HEIC-unsupported image, or fonts somehow missing from the bundle, would therefore
 * crash the app instead of showing an error.
 *
 * This object wraps the whole sequence and rethrows **every** render-path failure as a single
 * [IosRenderException] (tagged with the failing [IosRenderStage] and preserving the original
 * message + cause), annotated [Throws] so the generated Swift API is `throws`. Swift then does
 * `do { try … } catch { … }` and surfaces `WatermarkWorkflow.State.failure(...)`.
 *
 * It does **NOT** swallow failures into blank images or default fonts — a failure is always surfaced
 * as a thrown error; only the success path returns an [IosRenderedPng]. commonMain/Android renderer
 * behaviour is unchanged; this is a pure iOS-edge wrapper over the existing accepted APIs (no new
 * dependency, no compose-resources).
 */
object IosWatermarkRenderBridge {

    /**
 * Build the bundled font family, watermark [imageBytes] over a Skia-decoded background, and Skia-
 * Encode the result to PNG — returning bytes + dimensions in [IosRenderedPng]. Any failure at the * font / render(+decode) / encode stage is rethrown as [IosRenderException] (Swift-catchable).
 *
 * Defaults mirror the prior Swift call site (`WatermarkWorkflow.renderBlocking`): REPEAT tiling,
 * 24f text, 315° rotation, 40/40 gaps, centre offset, opaque. Font faces use the
 * [IosFontLoader] defaults loaded from [bundle].
     */
    @Throws(IosRenderException::class)
    fun renderWatermarkedPng(
        imageBytes: ByteArray,
        text: String,
        tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
        textSize: Float = 24f,
        degree: Float = 315f,
        hGapPercent: Int = 40,
        vGapPercent: Int = 40,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        alpha: Float = 1f,
        // ARGB text color (default amber #FFB800 = WaterMark.default.textColor); aligns the iOS
        // render to the shared default, replacing the prior hardcoded white.
        colorArgb: Int = WaterMark.default.textColor,
        // persisted text typeface (default Normal = WaterMark.default.textTypeface preserves the
        // prior regular output); mapped to Compose fontWeight/fontStyle in IosWatermarkRenderer.
        typeface: TextTypeface = WaterMark.default.textTypeface,
        // persisted text paint style (default Fill = WaterMark.default.textStyle preserves the
        // prior filled output); mapped to a Compose text drawStyle in IosWatermarkRenderer.
        textStyle: TextPaintStyle = WaterMark.default.textStyle,
        latinFirst: Boolean = true,
        bundle: NSBundle = NSBundle.mainBundle,
    ): IosRenderedPng {
        val fontFamily = try {
            IosFontLoader.bundledFontFamily(latinFirst = latinFirst, bundle = bundle)
        } catch (t: Throwable) {
            throw IosRenderException(IosRenderStage.FONT, t.message ?: "bundled font load failed", t)
        }

        val composed: ImageBitmap = try {
            IosWatermarkRenderer.composeOverImage(
                imageBytes = imageBytes,
                text = text,
                fontFamily = fontFamily,
                tileMode = tileMode,
                textSize = textSize,
                degree = degree,
                hGapPercent = hGapPercent,
                vGapPercent = vGapPercent,
                offsetX = offsetX,
                offsetY = offsetY,
                alpha = alpha,
                colorArgb = colorArgb,
                typeface = typeface,
                textStyle = textStyle,
            )
        } catch (t: Throwable) {
            // RENDER covers decode (IosImageDecoder, inside composeOverImage), cell rasterization,
            // tile-mode validation, and composition.
            throw IosRenderException(IosRenderStage.RENDER, t.message ?: "watermark render failed", t)
        }

        val png: ByteArray = try {
            IosWatermarkRenderer.encodePng(composed)
        } catch (t: Throwable) {
            throw IosRenderException(IosRenderStage.ENCODE, t.message ?: "PNG encode failed", t)
        }

        return IosRenderedPng(png = png, width = composed.width, height = composed.height)
    }

    /**
 * The **icon (image-watermark) variant** of [renderWatermarkedPng]. Watermarks [imageBytes] * with the persisted icon [iconBytes] via the [IosWatermarkRenderer.composeIconOverImage]
 * (decode background + icon → render the icon cell → compose), then Skia-encodes to PNG. There is **no
 * FONT stage** (image watermarks have no text). A decode/render failure is rethrown as
 * [IosRenderException]`(RENDER, …)` and an encode failure as `(ENCODE, …)` — Swift-catchable, never a
 * raw Kotlin/Native crash.
 *
 * Icon scale follows the renderer contract: `scaleRatio = textSize / ICON_SCALE_REFERENCE_TEXT_SIZE`
 * (14f ⇒ 1×) is computed **here** from [textSize], so the 14f reference constant stays in Kotlin and
 * Swift passes only the persisted `WaterMark.textSize`. [tileMode] must be REPEAT or CLAMP. iOS icon
 * rendering is **perceptual, not byte-parity** with Android `buildIconShader`.
     */
    @Throws(IosRenderException::class)
    fun renderIconWatermarkedPng(
        imageBytes: ByteArray,
        iconBytes: ByteArray,
        tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
        textSize: Float = 24f,
        degree: Float = 315f,
        hGapPercent: Int = 40,
        vGapPercent: Int = 40,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        alpha: Float = 1f,
    ): IosRenderedPng {
        val composed: ImageBitmap = try {
            IosWatermarkRenderer.composeIconOverImage(
                imageBytes = imageBytes,
                iconBytes = iconBytes,
                tileMode = tileMode,
                degree = degree,
                hGapPercent = hGapPercent,
                vGapPercent = vGapPercent,
                offsetX = offsetX,
                offsetY = offsetY,
                scaleRatio = textSize / WatermarkCellComposer.ICON_SCALE_REFERENCE_TEXT_SIZE,
                alpha = alpha,
            )
        } catch (t: Throwable) {
            // RENDER covers decode (background + icon via IosImageDecoder, inside composeIconOverImage),
            // icon-cell rasterization, tile-mode validation, and composition.
            throw IosRenderException(IosRenderStage.RENDER, t.message ?: "icon watermark render failed", t)
        }

        val png: ByteArray = try {
            IosWatermarkRenderer.encodePng(composed)
        } catch (t: Throwable) {
            throw IosRenderException(IosRenderStage.ENCODE, t.message ?: "PNG encode failed", t)
        }

        return IosRenderedPng(png = png, width = composed.width, height = composed.height)
    }
}

/** The render-pipeline stage that failed — tags an [IosRenderException] for diagnostics/UI. */
enum class IosRenderStage {
    /** Building the bundled Latin+CJK font family from the app bundle. */
    FONT,

    /** Decoding the source bytes + rasterizing/compositing the watermark cell over the image. */
    RENDER,

    /** Skia-encoding the composed bitmap to PNG. */
    ENCODE,
}

/**
 * The single Swift-catchable error type for the iOS render boundary. Carries the failing [stage] plus
 * The original [message]/cause so Swift can show a precise `State.failure`. Bridged to Swift as a * `throws` error via [IosWatermarkRenderBridge]'s [Throws] annotation.
 */
class IosRenderException(
    val stage: IosRenderStage,
    message: String?,
    cause: Throwable?,
) : Exception(message, cause)

/** Immutable success holder: the encoded PNG [png] and the composed image [width] × [height]. */
class IosRenderedPng(
    val png: ByteArray,
    val width: Int,
    val height: Int,
)
