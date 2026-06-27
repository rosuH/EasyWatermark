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
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode

/**
 * S4d-19: the **Desktop full-image watermark composition** — extends the S4d-18
 * [DesktopWatermarkTextRenderer] from "one text cell" to a watermarked-photo PNG over a sample
 * background. It is the Desktop half of the composition step (the commonMain analogue of Android
 * `WatermarkRenderer.compose`, which Android keeps native per S4d-17 Option C / S4d-8 Option A).
 *
 * Split of responsibilities (ADR-0004 boundary):
 *  - **commonMain** [WatermarkCellComposer.composeOverBackground] does the platform-neutral drawing
 *    (background + REPEAT grid tile / CLAMP single decal) — identical on Desktop and the future iOS.
 *  - **desktopMain** (this object) owns the platform pieces: a **deterministic generated** sample
 *    background (no binary asset; pure Compose graphics) and AWT/`ImageIO` PNG encode (via
 *    [DesktopWatermarkTextRenderer.encodePng]), plus a **Compose-free** result holder for `:desktopApp`.
 *
 * SCOPE: Desktop only. No Android production renderer change, no compose-resources.
 */
object DesktopWatermarkComposer {

    /**
     * A deterministic, asset-free sample background: a dark base with regular lighter diagonal-ish
     * bands so (a) white watermark ink is clearly visible against it, and (b) it is visually distinct
     * from a flat fill in inspection. Pure Compose graphics → identical bytes on every run (no
     * `Math.random`/time), which the determinism test relies on.
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
            // Dark base (opaque) so white watermark text contrasts strongly.
            drawRect(color = Color(0xFF1E2630))
            // Regular vertical bands in a slightly lighter tone — deterministic, no white.
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
     * Compose a full watermarked sample [ImageBitmap]: generate the sample background, render ONE text
     * cell via [DesktopWatermarkTextRenderer.renderTextCell] (bundled Latin+CJK font), and composite via
     * the shared [WatermarkCellComposer.composeOverBackground]. Text is sized image-space to [bgWidth].
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
     * A **Compose-free** composed result (dims + PNG bytes) for the plain-JVM `:desktopApp`, mirroring
     * S4d-18's `RenderedTextCell` (keeps `androidx.compose.ui` off `:desktopApp`'s compile classpath).
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

    // ---- S4d-20A: real-image (ImageIO-decoded) composition ---------------------------------------

    /**
     * Compose-free helper that produces a **deterministic encoded-PNG fixture** (the generated sample
     * background, AWT-encoded). It is the input a real-image path consumes: a caller can feed these bytes
     * straight back through [DesktopImageDecoder.decode] (a genuine `ImageIO` decode) and watermark the
     * result — exercising the platform decode path with no checked-in binary asset. Returns plain
     * `ByteArray` so `:desktopApp` needs no `androidx.compose.ui` on its compile classpath.
     */
    fun sampleBackgroundPng(width: Int = 640, height: Int = 480): ByteArray =
        DesktopWatermarkTextRenderer.encodePng(sampleBackground(width, height))

    /**
     * S4d-20A: watermark a **real, `ImageIO`-decoded** image. [imageBytes] are decoded via
     * [DesktopImageDecoder] (the platform decode boundary) into an [ImageBitmap] background, a text [cell]
     * is rendered at the decoded image's width (image-space sizing), and the two are composited through the
     * shared [WatermarkCellComposer.composeOverBackground]. Output is sized to the decoded image. Returns a
     * Compose-free [ComposedImage] (dims + PNG bytes) for `:desktopApp`.
     *
     * This is the realistic Desktop pipeline: decode (platform) → render cell (commonMain) → compose
     * (commonMain) → encode (platform). Decode/encode stay platform-side; commonMain stays decode-free.
     *
     * S4d-122/123: drives the persisted text fields [colorArgb] (ARGB), [typeface] ([TextTypeface]), and
     * [textStyle] ([TextPaintStyle]) through the shared text renderer. Defaults are the shared
     * `WaterMark.default` values. Icon watermark and output-format remain out of scope (PNG only).
     */
    fun composeOverRealImage(
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
        latinFirst: Boolean = true,
        colorArgb: Int = WaterMark.default.textColor,
        typeface: TextTypeface = TextTypeface.Normal,
        textStyle: TextPaintStyle = TextPaintStyle.Fill,
        // S4d-127: output encoding. Defaults to PNG (quality ignored for PNG) so existing callers + the
        // PNG-magic goldens get byte-identical output; pass ImageFormat.JPEG + a quality (0..100) for JPEG.
        // The Desktop save flow does NOT yet consume this (persisted-config wiring is S4d-128).
        format: ImageFormat = ImageFormat.PNG,
        quality: Int = 100,
    ): ComposedImage {
        val background = DesktopImageDecoder.decode(imageBytes) // genuine ImageIO decode
        val cell = DesktopWatermarkTextRenderer.renderTextCell(
            text = text,
            textSize = textSize,
            imageWidth = background.width,
            degree = degree,
            color = Color(colorArgb),
            hGapPercent = hGapPercent,
            vGapPercent = vGapPercent,
            latinFirst = latinFirst,
            typeface = typeface,
            textStyle = textStyle,
        )
        val composed = WatermarkCellComposer.composeOverBackground(
            background = background,
            cell = cell,
            tileMode = tileMode,
            offsetX = offsetX,
            offsetY = offsetY,
            alpha = alpha,
        )
        return ComposedImage(
            composed.width,
            composed.height,
            DesktopWatermarkTextRenderer.encode(composed, format, quality),
        )
    }
}
