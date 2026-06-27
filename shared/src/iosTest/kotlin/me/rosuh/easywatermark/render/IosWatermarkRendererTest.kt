package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S4d-20B: the **iOS renderer proof** (runs on `iosSimulatorArm64Test`). Proves the accepted commonMain
 * pipeline executes on the iOS (Skiko) target end-to-end: generate an image → Skia-encode → Skia-decode
 * ([IosImageDecoder]) → render a text cell + compose ([IosWatermarkRenderer]) → Skia-encode. Uses
 * [FontFamily.Default] (iOS system font) so the runtime proof does not depend on packaging the bundled CJK
 * font into an iOS bundle (that is C5; the bundled-font boundary [IosTextRasterEnv.bundledFontFamily] is
 * compile-proven separately). Structural/perceptual assertions, not byte-exact host pixels.
 */
class IosWatermarkRendererTest {

    private val w = 128
    private val h = 96

    /** A small deterministic background (dark base + one lighter band) drawn via commonMain Compose graphics. */
    private fun makeBackground(): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            drawRect(color = Color(0xFF1E2630))
            drawRect(color = Color(0xFF2C3846), topLeft = Offset(0f, 0f), size = Size(w / 3f, h.toFloat()))
        }
        return bmp
    }

    private fun nonBlank(b: ImageBitmap): Int {
        val p = b.toPixelMap(); var n = 0
        for (y in 0 until p.height) for (x in 0 until p.width) if (p[x, y].alpha > 0f) n++
        return n
    }

    /**
     * S4d-115: a small deterministic, **opaque, non-uniform** icon (magenta field + a white inner square)
     * so the icon raster is clearly visible and asymmetric under rotation. Drawn via commonMain Compose
     * graphics; encoded to PNG by the test when bytes are needed (exercising the [IosImageDecoder] path).
     */
    private fun makeIcon(): ImageBitmap {
        val s = 24
        val bmp = ImageBitmap(s, s, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(s.toFloat(), s.toFloat())) {
            drawRect(color = Color(0xFFEE22AA))
            drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(6f, 6f), size = Size(8f, 8f))
        }
        return bmp
    }

    @Test
    fun env_builds() {
        val env = IosTextRasterEnv.textRasterEnv()
        assertEquals(1f, env.density.density, "iOS text-raster env density must be 1f (image-space)")
    }

    @Test
    fun skia_encode_decode_round_trips_dimensions() {
        val png = IosWatermarkRenderer.encodePng(makeBackground())
        assertTrue(png.size > 8, "encoded PNG must be non-trivial")
        val decoded = IosImageDecoder.decode(png)
        assertEquals(w, decoded.width, "decoded width must match source")
        assertEquals(h, decoded.height, "decoded height must match source")
        assertTrue(nonBlank(decoded) > 0, "decoded image must have visible pixels")
    }

    @Test
    fun compose_over_decoded_image_is_sized_and_changes_pixels() {
        val srcPng = IosWatermarkRenderer.encodePng(makeBackground())
        val decoded = IosImageDecoder.decode(srcPng)
        val composed = IosWatermarkRenderer.composeOverImage(
            imageBytes = srcPng, text = "请勿转载\nDO NOT", tileMode = WatermarkTileMode.REPEAT,
        )
        assertEquals(decoded.width, composed.width, "composed width == decoded width")
        assertEquals(decoded.height, composed.height, "composed height == decoded height")
        // Re-decode both and count changed pixels: the rotated/tiled watermark cell must alter the image.
        val before = decoded.toPixelMap()
        val after = composed.toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != after[x, y]) changed++
        assertTrue(changed > 0, "watermark composition must change pixels vs the decoded background (changed=$changed)")
    }

    @Test
    fun pipeline_is_deterministic() {
        val srcPng = IosWatermarkRenderer.encodePng(makeBackground())
        val a = IosWatermarkRenderer.encodePng(
            IosWatermarkRenderer.composeOverImage(srcPng, "请勿转载", tileMode = WatermarkTileMode.CLAMP),
        )
        val b = IosWatermarkRenderer.encodePng(
            IosWatermarkRenderer.composeOverImage(srcPng, "请勿转载", tileMode = WatermarkTileMode.CLAMP),
        )
        assertTrue(a.contentEquals(b), "iOS compose pipeline must be deterministic (identical PNG bytes)")
    }

    @Test
    fun decoder_rejects_undecodable_bytes() {
        assertFailsWith<IllegalStateException> { IosImageDecoder.decode(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    /**
     * S4d-115: [IosWatermarkRenderer.renderIconCell] (wrapping commonMain `composeIconCell`) rasters a
     * visible icon cell on the iOS Skiko backend. Rotated 315° (the production default) at 2× scale to
     * exercise rotation + scaling. Perceptual proof (non-blank), not byte-parity with Android.
     */
    @Test
    fun renderIconCell_produces_nonblank_cell() {
        val cell = IosWatermarkRenderer.renderIconCell(
            icon = makeIcon(), degree = 315f, scaleRatio = 2f,
        )
        assertTrue(cell.width > 0 && cell.height > 0, "icon cell must have positive dimensions")
        assertTrue(nonBlank(cell) > 0, "icon cell must have visible (non-transparent) pixels")
    }

    /**
     * S4d-115: [IosWatermarkRenderer.composeIconOverImage] decodes the background + icon bytes
     * ([IosImageDecoder]), renders the icon cell, and tiles it over the background — sized to the
     * background and changing pixels. The icon bytes are PNG-encoded then re-decoded, exercising the full
     * decode path. REPEAT tiling so at least one icon tile lands on the image.
     */
    @Test
    fun composeIconOverImage_is_sized_and_changes_pixels() {
        val bgPng = IosWatermarkRenderer.encodePng(makeBackground())
        val iconPng = IosWatermarkRenderer.encodePng(makeIcon())
        val decoded = IosImageDecoder.decode(bgPng)
        val composed = IosWatermarkRenderer.composeIconOverImage(
            imageBytes = bgPng, iconBytes = iconPng, tileMode = WatermarkTileMode.REPEAT, scaleRatio = 2f,
        )
        assertEquals(decoded.width, composed.width, "composed width == background width")
        assertEquals(decoded.height, composed.height, "composed height == background height")
        val before = decoded.toPixelMap()
        val after = composed.toPixelMap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) if (before[x, y] != after[x, y]) changed++
        assertTrue(changed > 0, "icon composition must change pixels vs the decoded background (changed=$changed)")
    }
}
