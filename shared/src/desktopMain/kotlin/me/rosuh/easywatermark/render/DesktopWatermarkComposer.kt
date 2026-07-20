package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode

/**
 * Desktop composition over sample or real decoded image bytes (ImageIO + Skiko encode).
 *
 * Production real-image paint uses [composeRealImage] → [CommonWatermarkPipeline] (C2).
 * Deterministic [composeSample] / [composeSampleResult] remain sample/witness helpers and may call
 * primitives directly.
 */
object DesktopWatermarkComposer {

    /**
     * A deterministic, asset-free sample background: a dark base with regular lighter diagonal-ish
     * bands so (a) white watermark ink is clearly visible against it, and (b) it is visually distinct
     * from a flat fill in inspection. Pure Compose graphics → identical bytes on every run.
     */
    fun sampleBackground(width: Int, height: Int): ImageBitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color = Color(0xFF1E2630))
            val band = (w / 16).coerceAtLeast(4)
            var x = 0
            var i = 0
            while (x < w) {
                if (i % 2 == 0) {
                    drawRect(
                        color = Color(0xFF2C3846),
                        topLeft = Offset(x.toFloat(), 0f),
                        size = Size(band.toFloat(), h.toFloat()),
                    )
                }
                x += band
                i++
            }
        }
        return bmp
    }

    /**
     * Compose a full watermarked sample [ImageBitmap] via direct cell + [WatermarkCellComposer]
     * (sample/witness helper — not the product real-image adapter).
     */
    fun composeSample(
        text: String,
        bgWidth: Int = 640,
        bgHeight: Int = 480,
        tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
        textSize: Float = 24f,
        degree: Float = 315f,
        hGapPercent: Int = 40,
        vGapPercent: Int = 40,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        alpha: Float = 1f,
        latinFirst: Boolean = true,
    ): ImageBitmap {
        val background = sampleBackground(bgWidth, bgHeight)
        val cell = DesktopWatermarkTextRenderer.renderTextCell(
            text = text,
            textSize = textSize,
            imageWidth = bgWidth,
            degree = degree,
            color = Color.White,
            hGapPercent = hGapPercent,
            vGapPercent = vGapPercent,
            latinFirst = latinFirst,
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
     * A Compose-free composed result (dims + encoded bytes) for plain-JVM `:desktopApp`.
     * [png] holds encoded output bytes (PNG or JPEG depending on the encode path used).
     */
    data class ComposedImage(val width: Int, val height: Int, val png: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is ComposedImage && width == other.width &&
                height == other.height && png.contentEquals(other.png))

        override fun hashCode(): Int = (width * 31 + height) * 31 + png.contentHashCode()
    }

    /** Compose a watermarked sample and return its dims + PNG bytes (used by `:desktopApp`). */
    fun composeSampleResult(
        text: String,
        bgWidth: Int = 640,
        bgHeight: Int = 480,
        tileMode: WatermarkTileMode = WatermarkTileMode.REPEAT,
        textSize: Float = 24f,
        degree: Float = 315f,
        hGapPercent: Int = 40,
        vGapPercent: Int = 40,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
        alpha: Float = 1f,
        latinFirst: Boolean = true,
    ): ComposedImage {
        val composed = composeSample(
            text, bgWidth, bgHeight, tileMode, textSize, degree,
            hGapPercent, vGapPercent, offsetX, offsetY, alpha, latinFirst,
        )
        return ComposedImage(composed.width, composed.height, DesktopWatermarkTextRenderer.encodePng(composed))
    }

    /**
     * Deterministic encoded-PNG fixture (generated sample background, AWT-encoded) for real-image
     * decode tests without checked-in binary assets.
     */
    fun sampleBackgroundPng(width: Int = 640, height: Int = 480): ByteArray =
        DesktopWatermarkTextRenderer.encodePng(sampleBackground(width, height))

    /**
     * Production Desktop real-image composition (C2): decode → [CommonWatermarkPipeline.compose] →
     * encode. Paint policy (tile, alpha-once, geometry, Text/Image) is owned by the common pipeline.
     * Desktop supplies EXIF-baked decode, bundled Latin+CJK [FontFamily], icon file bytes (caller),
     * and encode format/quality from [DesktopRenderRequest.prefs].
     *
     * [iconBytes] is required when [DesktopRenderRequest.config] is Image mode; ignored for Text.
     */
    fun composeRealImage(
        imageBytes: ByteArray,
        request: DesktopRenderRequest,
        iconBytes: ByteArray? = null,
    ): ComposedImage {
        val background = DesktopImageDecoder.decode(imageBytes)
        val icon = if (request.config.markMode == WatermarkMode.Image) {
            require(iconBytes != null && iconBytes.isNotEmpty()) {
                "Image-mode composeRealImage requires non-empty iconBytes"
            }
            DesktopImageDecoder.decode(iconBytes)
        } else {
            null
        }
        val composed = CommonWatermarkPipeline.compose(
            background = background,
            config = request.config,
            env = DesktopWatermarkTextRenderer.textRasterEnv(),
            icon = icon,
            offsetX = request.offsetX,
            offsetY = request.offsetY,
            fontFamily = DesktopWatermarkTextRenderer.bundledLatinCjkFontFamily(),
        )
        return ComposedImage(
            composed.width,
            composed.height,
            DesktopWatermarkTextRenderer.encode(
                composed,
                request.prefs.outputFormat,
                request.prefs.compressLevel,
            ),
        )
    }
}
