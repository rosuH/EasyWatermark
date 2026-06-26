package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * S4d-18: the **Desktop (JVM/Skiko) production watermark text renderer** — the first non-test use of
 * the bundled commonMain text path. It is the Desktop half of S4d-17 **Option C**: Android watermark
 * text stays native (`WatermarkRenderer.buildTextShader` / `StaticLayout`), while the bundled
 * commonMain [WatermarkCellComposer.composeTextCell] is the Desktop/iOS-first renderer.
 *
 * This object owns the two irreducibly-platform pieces ADR-0004 calls out for the text raster:
 *  1. the **font resolver** — desktop Skiko's `createFontFamilyResolver()` (no `Context`), and
 *  2. the **bundled font bytes** — Noto Sans (Latin) + Noto Sans SC (CJK), loaded from this module's
 *     desktop **main** resources (`shared/src/desktopMain/resources/fonts/`) via the classpath, with
 *     the Skiko byte-`Font` factory (`androidx.compose.ui.text.platform.Font`). **No
 *     compose-resources / CMP-9547** (per the standing CMP constraint).
 *
 * Everything else (measure, size, rotate, paint) is the shared, platform-neutral
 * [WatermarkCellComposer.composeTextCell] — so Desktop and the eventual iOS renderer composite text
 * identically, and the shared `WatermarkGeometry` drives cell sizing on every platform.
 *
 * Density is `Density(1f)` to match the production image-space convention (`1.sp == 1px`, S3a) used by
 * the Android measurement seam — the watermark is a fraction of the image, independent of host DPI.
 *
 * SCOPE: Desktop only. This does NOT touch Android production text (still native) and is NOT an
 * Android draw-swap. Verified by `:shared:desktopTest` (`DesktopTextRendererGoldenTest`) and exercised
 * by `:desktopApp`.
 */
object DesktopWatermarkTextRenderer {

    /** The reference width used for image-space text sizing (mirrors `WatermarkRenderer.REF_WIDTH`). */
    const val REF_WIDTH: Int = 1000

    /**
     * The bundled Latin + CJK watermark [FontFamily] for Desktop, loaded from
     * `desktopMain/resources/fonts/` on the classpath. [latinFirst] lists the Latin face first (the
     * owner's Latin+CJK order, S4d-16) so Latin keeps near-system line metrics while CJK resolves via
     * fallback; `false` keeps the CJK-first order. Bold/Italic are synthesized (no bundled
     * bold/italic faces, per ADR-0010).
     */
    fun bundledLatinCjkFontFamily(latinFirst: Boolean = true): FontFamily {
        fun bytes(path: String): ByteArray =
            DesktopWatermarkTextRenderer::class.java.classLoader!!.getResourceAsStream(path)
                ?.use { it.readBytes() }
                ?: error("S4d-18 bundled desktop font not found on classpath: $path")
        val latin = Font("NotoSansLatin", bytes("fonts/NotoSans-Regular.ttf"), FontWeight.Normal, FontStyle.Normal)
        val cjk = Font("NotoSansSC", bytes("fonts/NotoSansSC-Regular.otf"), FontWeight.Normal, FontStyle.Normal)
        return if (latinFirst) FontFamily(latin, cjk) else FontFamily(cjk, latin)
    }

    /** The Desktop (Skiko) text-raster environment: desktop resolver + image-space density. */
    fun textRasterEnv(density: Density = Density(1f)): TextRasterEnv = TextRasterEnv(
        fontFamilyResolver = createFontFamilyResolver(),
        density = density,
        layoutDirection = LayoutDirection.Ltr,
    )

    /**
     * Render ONE watermark text cell through the shared [WatermarkCellComposer.composeTextCell] with
     * the bundled Desktop font, returning the offscreen [ImageBitmap].
     *
     * @param text        watermark text (may contain `\n` for multiline and CJK)
     * @param textSize    the `WaterMark.textSize` value (image-space fraction of [imageWidth])
     * @param imageWidth  target image width; `fontPx = textSize * imageWidth / REF_WIDTH` (S3a)
     * @param degree      rotation in degrees (matches `WaterMark.degree`)
     * @param color       fill colour (default white, like the production text cell)
     * @param hGapPercent horizontal gap percent; @param vGapPercent vertical gap percent
     * @param latinFirst  font fallback order (see [bundledLatinCjkFontFamily])
     */
    fun renderTextCell(
        text: String,
        textSize: Float = 24f,
        imageWidth: Int = REF_WIDTH,
        degree: Float = 0f,
        color: Color = Color.White,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        latinFirst: Boolean = true,
    ): ImageBitmap {
        val fontPx = textSize * imageWidth / REF_WIDTH
        val content = WatermarkTextContent(
            text = text,
            style = TextStyle(fontSize = fontPx.sp, fontFamily = bundledLatinCjkFontFamily(latinFirst)),
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

    /** Convenience: render a text cell and return its PNG bytes in one call (used by `:desktopApp`). */
    fun renderTextCellPng(
        text: String,
        textSize: Float = 24f,
        imageWidth: Int = REF_WIDTH,
        degree: Float = 0f,
        color: Color = Color.White,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        latinFirst: Boolean = true,
    ): ByteArray = encodePng(
        renderTextCell(text, textSize, imageWidth, degree, color, hGapPercent, vGapPercent, latinFirst),
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
     * value class: exposing a value-class parameter across the module boundary mangles the synthetic
     * `$default` method name and breaks a plain-JVM caller at runtime. Fill colour is white (the
     * production text-cell default); colour-parameterized rendering uses [renderTextCell] within
     * Compose-aware code. [colorArgb] takes a plain packed ARGB int if a non-white fill is needed.
     */
    fun renderTextCellResult(
        text: String,
        textSize: Float = 24f,
        imageWidth: Int = REF_WIDTH,
        degree: Float = 0f,
        hGapPercent: Int = 0,
        vGapPercent: Int = 0,
        latinFirst: Boolean = true,
        colorArgb: Int = 0xFFFFFFFF.toInt(),
    ): RenderedTextCell {
        val cell = renderTextCell(
            text, textSize, imageWidth, degree, Color(colorArgb), hGapPercent, vGapPercent, latinFirst,
        )
        return RenderedTextCell(cell.width, cell.height, encodePng(cell))
    }
}
