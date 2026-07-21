package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import platform.Foundation.NSBundle

/**
 * iOS Swift-catchable render boundary (C3: routes through [IosFinalRenderSpine] + common pipeline).
 *
 * Preserves PNG-return ABI and FONT/RENDER/ENCODE stages for legacy Swift callers. Production
 * format policy lives on [IosExportPipelinePort], not here.
 *
 * RENDER vs ENCODE mapping is **structural** (separate try/catch around compose vs encode), not
 * message-string heuristics.
 */
object IosWatermarkRenderBridge {

    /**
     * Test-only encode override so ENCODE-stage failures can be forced without message parsing.
     * Production path leaves this null and uses [IosFinalRenderSpine.encodeExplicitSrgb].
     */
    internal var encodeOverrideForTests: ((ImageBitmap, ImageFormat, Int) -> ByteArray)? = null

    /**
     * Build the bundled font family, watermark [imageBytes], and encode PNG via the final spine.
     * Defaults mirror the prior Swift call site. Failures rethrow as [IosRenderException].
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
        colorArgb: Int = WaterMark.default.textColor,
        typeface: TextTypeface = WaterMark.default.textTypeface,
        textStyle: TextPaintStyle = WaterMark.default.textStyle,
        latinFirst: Boolean = true,
        bundle: NSBundle = NSBundle.mainBundle,
    ): IosRenderedPng {
        val fontFamily = try {
            IosFontLoader.bundledFontFamily(latinFirst = latinFirst, bundle = bundle)
        } catch (t: Throwable) {
            throw IosRenderException(IosRenderStage.FONT, t.message ?: "bundled font load failed", t)
        }

        val config = WaterMark.default.copy(
            text = text,
            markMode = WatermarkMode.Text,
            tileMode = tileMode,
            textSize = textSize,
            degree = degree,
            hGap = hGapPercent,
            vGap = vGapPercent,
            alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
            textColor = colorArgb,
            textTypeface = typeface,
            textStyle = textStyle,
        )
        val request = IosRenderRequest(
            config = config,
            prefs = UserPreferences(ImageFormat.PNG, 100),
            offsetX = offsetX,
            offsetY = offsetY,
        )

        return composeThenEncodePng(
            imageBytes = imageBytes,
            request = request,
            iconBytes = null,
            fontFamily = fontFamily,
            failMessage = "watermark render failed",
        )
    }

    /**
     * Icon (image-watermark) variant — no FONT stage. PNG compatibility only.
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
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            tileMode = tileMode,
            textSize = textSize,
            degree = degree,
            hGap = hGapPercent,
            vGap = vGapPercent,
            alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
        )
        val request = IosRenderRequest(
            config = config,
            prefs = UserPreferences(ImageFormat.PNG, 100),
            offsetX = offsetX,
            offsetY = offsetY,
        )
        return composeThenEncodePng(
            imageBytes = imageBytes,
            request = request,
            iconBytes = iconBytes,
            fontFamily = null,
            failMessage = "icon watermark render failed",
        )
    }

    /**
     * Structurally separate RENDER (decode/compose) from ENCODE. No message-string stage guessing.
     */
    private fun composeThenEncodePng(
        imageBytes: ByteArray,
        request: IosRenderRequest,
        iconBytes: ByteArray?,
        fontFamily: androidx.compose.ui.text.font.FontFamily?,
        failMessage: String,
    ): IosRenderedPng {
        val composed = try {
            IosFinalRenderSpine.composeForExport(
                imageBytes = imageBytes,
                request = request,
                iconBytes = iconBytes,
                fontFamily = fontFamily,
            )
        } catch (t: Throwable) {
            throw IosRenderException(
                IosRenderStage.RENDER,
                t.message ?: failMessage,
                t,
            )
        }

        val encodedBytes = try {
            val override = encodeOverrideForTests
            if (override != null) {
                override(composed, ImageFormat.PNG, 100)
            } else {
                IosFinalRenderSpine.encodeExplicitSrgb(composed, ImageFormat.PNG, 100)
            }
        } catch (t: Throwable) {
            throw IosRenderException(
                IosRenderStage.ENCODE,
                t.message ?: failMessage,
                t,
            )
        }

        return IosRenderedPng(
            png = encodedBytes,
            width = composed.width,
            height = composed.height,
        )
    }
}

/** The render-pipeline stage that failed — tags an [IosRenderException] for diagnostics/UI. */
enum class IosRenderStage {
    FONT,
    RENDER,
    ENCODE,
}

/**
 * Single Swift-catchable error type for the iOS render boundary.
 */
class IosRenderException(
    val stage: IosRenderStage,
    message: String?,
    cause: Throwable?,
) : Exception(message, cause)

/** Immutable success holder: encoded PNG [png] and composed [width] × [height]. */
class IosRenderedPng(
    val png: ByteArray,
    val width: Int,
    val height: Int,
)
