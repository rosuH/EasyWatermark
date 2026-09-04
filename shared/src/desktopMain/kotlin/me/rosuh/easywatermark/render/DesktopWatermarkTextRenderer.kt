package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * The **Desktop (JVM/Skiko) production watermark text renderer** — platform half of the
 * commonMain text path ([WatermarkCellComposer.composeTextCell]). Android production also uses
 * commonMain text via `AndroidCommonRaster` (ADR-0018); native `WatermarkRenderer` / `StaticLayout`
 * is measurement/golden oracle only (not byte-identical, especially CJK).
 *
 * Owns the irreducibly-platform **font resolver** — desktop Skiko's `createFontFamilyResolver()`
 * (no `Context`). Production Text mode uses [FontFamily.Default] (ADR-0025); multi-MB Noto faces
 * are test-only under `desktopTest/resources/fonts/`. **No compose-resources / CMP-9547.**
 *
 * Everything else (measure, size, rotate, paint) is shared [WatermarkCellComposer.composeTextCell].
 * Density is `Density(1f)` (image-space: `1.sp == 1px`, S3a).
 */
object DesktopWatermarkTextRenderer {

    /**
     * H2: process-wide shared [FontFamily.Resolver] for [textRasterEnv] — avoids
     * `createFontFamilyResolver()` allocation on every raster call.
     */
    private val sharedFontFamilyResolver by lazy(LazyThreadSafetyMode.PUBLICATION) {
        createFontFamilyResolver()
    }

    /** The Desktop (Skiko) text-raster environment: shared resolver + image-space density. */
    fun textRasterEnv(density: Density = Density(1f)): TextRasterEnv = TextRasterEnv(
        fontFamilyResolver = sharedFontFamilyResolver,
        density = density,
        layoutDirection = LayoutDirection.Ltr,
    )

    /**
     * Render ONE watermark text cell through [WatermarkCellComposer.composeTextCell] with the
     * system-default family (ADR-0025).
     *
     * @param text watermark text (may contain `\n` for multiline and CJK)
     * @param textSize the `WaterMark.textSize` value (image-space fraction of [imageWidth])
     * @param imageWidth target image width; `fontPx = WatermarkGeometry.fontPx(textSize, imageWidth)` (S3a)
     * @param degree rotation in degrees (matches `WaterMark.degree`)
     * @param color fill colour (default white, like the production text cell)
     * @param hGapPercent horizontal gap percent; @param vGapPercent vertical gap percent
     * @param typeface text typeface → Compose `fontWeight`/`fontStyle` (default Normal; synthesized)
     * @param textStyle paint style → Compose text `drawStyle` (default Fill)
     */
    fun renderTextCell(
        text: String,
        textSize: Float = 24f,
        imageWidth: Int = WatermarkGeometry.REF_WIDTH.toInt(),
        degree: Float = 0f,
        color: Color = Color.White,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        typeface: TextTypeface = TextTypeface.Normal,
        textStyle: TextPaintStyle = TextPaintStyle.Fill,
    ): ImageBitmap {
        val fontPx = WatermarkGeometry.fontPx(textSize, imageWidth)
        val (fontWeight, fontStyle) = typeface.toComposeFontStyle()
        val content = WatermarkTextContent(
            text = text,
            style = TextStyle(
                fontSize = fontPx.sp,
                fontFamily = FontFamily.Default,
                fontWeight = fontWeight,
                fontStyle = fontStyle,
                drawStyle = textStyle.toComposeDrawStyle(),
            ),
            color = color,
        )
        return WatermarkCellComposer.composeTextCell(
            env = textRasterEnv(),
            content = content,
            degree = degree,
            hGapPercent = hGapPercent,
            vGapPercent = vGapPercent,
        )
    }

    /** Encode an [ImageBitmap] to PNG bytes via AWT (`ImageIO`) — desktop-only. */
    fun encodePng(bitmap: ImageBitmap): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(bitmap.toAwtImage(), "png", out)
        return out.toByteArray()
    }

    /**
 * Encode an [ImageBitmap] to [format] bytes. **PNG delegates to [encodePng]** (lossless, * byte-identical to the existing path). **JPEG** flattens the ARGB `toAwtImage()` onto an opaque
 * `TYPE_INT_RGB` canvas first — the JDK ImageIO JPEG writer cannot encode alpha, so a naive
 * `ImageIO.write(argb, "jpg", …)` produces wrong/black output — then encodes at [quality]
 * (0..100 → `ImageWriteParam.compressionQuality` 0f..1f). JDK ImageIO only; **no new dependency**.
 *
 * added this as a capability; ** wired it into the Desktop save flow** —
 * `DesktopWatermarkFlow.runSaveFlow` reads the persisted `UserConfigRepository` prefs and passes the
 * `outputFormat`/`compressLevel` through to the composer (empty store → the shared `(JPEG, 80)` default).
 * The `composeRealImage` path encodes from [UserPreferences]; test goldens still request PNG for
 * byte-identical output.
     */
    fun encode(bitmap: ImageBitmap, format: ImageFormat, quality: Int = 100): ByteArray = when (format) {
        ImageFormat.PNG -> encodePng(bitmap)
        ImageFormat.JPEG -> encodeJpeg(bitmap, quality)
    }

    /** JPEG encode with alpha flattened onto opaque white (JPEG has no alpha) + explicit quality. */
    private fun encodeJpeg(bitmap: ImageBitmap, quality: Int): ByteArray {
        val argb = bitmap.toAwtImage()
        val rgb = BufferedImage(argb.width, argb.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, rgb.width, rgb.height)
            g.drawImage(argb, 0, 0, null)
        } finally {
            g.dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality.coerceIn(0, 100) / 100f
            }
            try {
                writer.write(null, IIOImage(rgb, null, null), param)
            } finally {
                writer.dispose()
            }
        }
        return out.toByteArray()
    }

    /** Convenience: render a text cell and return its PNG bytes in one call (used by `:desktopApp`). */
    fun renderTextCellPng(
        text: String,
        textSize: Float = 24f,
        imageWidth: Int = WatermarkGeometry.REF_WIDTH.toInt(),
        degree: Float = 0f,
        color: Color = Color.White,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
    ): ByteArray = encodePng(
        renderTextCell(text, textSize, imageWidth, degree, color, hGapPercent, vGapPercent),
    )

    /**
 * A **Compose-free** rendered result (cell dims + PNG bytes) so a plain-JVM consumer like
 * `:desktopApp` can render + report a watermark text cell without putting `androidx.compose.ui`
 * (`ImageBitmap`) on its own compile classpath. The renderer scaffold stays minimal.
     */
    data class RenderedTextCell(val width: Int, val height: Int, val png: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is RenderedTextCell && width == other.width &&
                height == other.height && png.contentEquals(other.png))

        override fun hashCode(): Int =
            (width * 31 + height) * 31 + png.contentHashCode()
    }

    /**
 * Render a text cell and return its dims + PNG bytes as a **Compose-free** holder (used by
 * `:desktopApp`). The signature deliberately avoids the `androidx.compose.ui.graphics.Color`
 * Value class: exposing a value-class parameter across the module boundary mangles the synthetic * `$default` method name and breaks a plain-JVM caller at runtime. Fill colour is white (the
 * production text-cell default); colour-parameterized rendering uses [renderTextCell] within
 * Compose-aware code. [colorArgb] takes a plain packed ARGB int if a non-white fill is needed.
     */
    fun renderTextCellResult(
        text: String,
        textSize: Float = 24f,
        imageWidth: Int = WatermarkGeometry.REF_WIDTH.toInt(),
        degree: Float = 0f,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        colorArgb: Int = 0xFFFFFFFF.toInt(),
    ): RenderedTextCell {
        val cell = renderTextCell(
            text, textSize, imageWidth, degree, Color(colorArgb), hGapPercent, vGapPercent,
        )
        return RenderedTextCell(cell.width, cell.height, encodePng(cell))
    }
}
