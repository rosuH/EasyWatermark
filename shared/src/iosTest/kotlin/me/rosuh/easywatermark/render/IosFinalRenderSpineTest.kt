package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sole Final Export decode/common-paint/encode matrix (C3).
 */
class IosFinalRenderSpineTest {

    private val bgColor = Color(0xFF203040)
    private val rgbEps = 0.02f

    private fun solid(w: Int, h: Int, color: Color = bgColor): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return bmp
    }

    private fun solidPng(w: Int, h: Int, color: Color = bgColor): ByteArray =
        IosWatermarkRenderer.encodePng(solid(w, h, color))

    private fun asymmetricIconPng(): ByteArray {
        val w = 48
        val h = 32
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color = Color.Blue)
            drawRect(color = Color.Red, topLeft = Offset.Zero, size = Size(w / 2f, h / 2f))
            drawRect(
                color = Color.White,
                topLeft = Offset(w * 0.75f, h * 0.75f),
                size = Size(w * 0.2f, h * 0.2f),
            )
        }
        return IosWatermarkRenderer.encodePng(bmp)
    }

    private fun transparentControlPng(w: Int = 64, h: Int = 48): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        // leave fully transparent
        return IosWatermarkRenderer.encodePng(bmp)
    }

    private data class Centroid(val x: Double, val y: Double, val count: Int)

    private fun changedCentroid(bgPng: ByteArray, outBytes: ByteArray): Centroid {
        val bg = IosImageDecoder.decode(bgPng).toPixelMap()
        val out = IosImageDecoder.decode(outBytes).toPixelMap()
        assertEquals(bg.width, out.width)
        assertEquals(bg.height, out.height)
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val bc = bg[x, y]
                val oc = out[x, y]
                if (abs(oc.red - bc.red) > rgbEps ||
                    abs(oc.green - bc.green) > rgbEps ||
                    abs(oc.blue - bc.blue) > rgbEps
                ) {
                    sx += x
                    sy += y
                    n++
                }
            }
        }
        if (n == 0) fail("no changed pixels vs background")
        return Centroid(sx / n, sy / n, n)
    }

    private fun textClampConfig(): WaterMark = WaterMark.default.copy(
        text = "TOP\nBOTTOM",
        textSize = 32f,
        textStyle = TextPaintStyle.Fill,
        textTypeface = TextTypeface.BoldItalic,
        tileMode = WatermarkTileMode.CLAMP,
        degree = 0f,
        hGap = 0,
        vGap = 0,
        alpha = 255,
        markMode = WatermarkMode.Text,
    )

    @Test
    fun textClamp_offsetPair_movesCentroid() {
        val w = 320
        val h = 240
        val bg = solidPng(w, h)
        val cfg = textClampConfig()
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val a = IosFinalRenderSpine.renderAndEncode(
            bg,
            IosRenderRequest(cfg, prefs, 0.17f, 0.83f),
            fontFamily = null,
        )
        val b = IosFinalRenderSpine.renderAndEncode(
            bg,
            IosRenderRequest(cfg, prefs, 0.83f, 0.17f),
            fontFamily = null,
        )
        assertEquals(w, a.width)
        assertEquals(h, b.height)
        val ca = changedCentroid(bg, a.bytes)
        val cb = changedCentroid(bg, b.bytes)
        val dx = cb.x - ca.x
        val dy = ca.y - cb.y
        assertTrue(dx >= 0.20 * w, "text centroid must move right ≥20% (dx=$dx)")
        assertTrue(dy >= 0.20 * h, "text centroid must move up ≥20% (dy=$dy)")
    }

    @Test
    fun iconClamp_offsetPair_movesCentroid() {
        val w = 320
        val h = 240
        val bg = solidPng(w, h)
        val icon = asymmetricIconPng()
        val cfg = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            textSize = 14f,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            hGap = 0,
            vGap = 0,
            alpha = 255,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val a = IosFinalRenderSpine.renderAndEncode(
            bg, IosRenderRequest(cfg, prefs, 0.17f, 0.83f), iconBytes = icon,
        )
        val b = IosFinalRenderSpine.renderAndEncode(
            bg, IosRenderRequest(cfg, prefs, 0.83f, 0.17f), iconBytes = icon,
        )
        val ca = changedCentroid(bg, a.bytes)
        val cb = changedCentroid(bg, b.bytes)
        assertTrue(cb.x - ca.x >= 0.20 * w, "icon centroid dx")
        assertTrue(ca.y - cb.y >= 0.20 * h, "icon centroid dy")
    }

    @Test
    fun jpeg_fullResolution_quality_srgb_and_whiteFlatten() {
        val bg = solidPng(256, 192)
        val cfg = WaterMark.default.copy(text = "JPEG", textSize = 28f, tileMode = WatermarkTileMode.REPEAT)
        val q20 = IosFinalRenderSpine.renderAndEncode(
            bg, IosRenderRequest(cfg, UserPreferences(ImageFormat.JPEG, 20), 0.5f, 0.5f),
        )
        val q95 = IosFinalRenderSpine.renderAndEncode(
            bg, IosRenderRequest(cfg, UserPreferences(ImageFormat.JPEG, 95), 0.5f, 0.5f),
        )
        assertEquals(ImageFormat.JPEG, q20.format)
        assertEquals(256, q20.width)
        assertEquals(192, q20.height)
        assertEquals(q20.bytes.size, q20.byteCount)
        assertTrue(q20.bytes[0] == 0xFF.toByte() && q20.bytes[1] == 0xD8.toByte() && q20.bytes[2] == 0xFF.toByte())
        assertEquals(q95.width, q20.width)
        assertTrue(q20.byteCount <= q95.byteCount, "q20 must not be larger than q95")

        // Product sRGB contract = explicit working surface before encode (not decoded container profile).
        assertExplicitSrgbWorkingSurface(ColorAlphaType.OPAQUE)
        assertTrue(q20.bytes[0] == 0xFF.toByte()) // still a real JPEG

        // Transparent source flattened to white on JPEG.
        val transparent = transparentControlPng(48, 32)
        val flat = IosFinalRenderSpine.renderAndEncode(
            transparent,
            IosRenderRequest(
                WaterMark.default.copy(text = " ", alpha = 0, tileMode = WatermarkTileMode.CLAMP),
                UserPreferences(ImageFormat.JPEG, 90),
                0.5f,
                0.5f,
            ),
        )
        val px = IosImageDecoder.decode(flat.bytes).toPixelMap()
        val c = px[px.width / 2, px.height / 2]
        assertTrue(c.red > 0.85f && c.green > 0.85f && c.blue > 0.85f, "JPEG must flatten to white not black")
    }

    @Test
    fun png_preservesAlpha_and_srgb() {
        val transparent = transparentControlPng(64, 48)
        val out = IosFinalRenderSpine.renderAndEncode(
            transparent,
            IosRenderRequest(
                WaterMark.default.copy(text = " ", alpha = 0, tileMode = WatermarkTileMode.CLAMP),
                UserPreferences(ImageFormat.PNG, 100),
                0.5f,
                0.5f,
            ),
        )
        assertEquals(ImageFormat.PNG, out.format)
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(out.bytes.take(8).toByteArray().contentEquals(magic))
        val px = IosImageDecoder.decode(out.bytes).toPixelMap()
        assertTrue(px[1, 1].alpha < 0.05f, "PNG must preserve transparency for empty watermark")
        // Product sRGB contract = explicit working surface (not decoded container profile).
        assertExplicitSrgbWorkingSurface(ColorAlphaType.PREMUL)
    }

    /**
     * Real EXIF orientation **7** (transverse): bright top-left block must decode upright to
     * bottom-right with swapped dims; final encode strips source APP1.
     */
    @Test
    fun orientation7_isUpright_swapsDimensions_and_stripsOrientation() {
        val baseW = 24
        val baseH = 16
        val oriented = jpegWithOrientation(orientation = 7, baseW = baseW, baseH = baseH)

        // Decode edge alone must already bake orientation 7 → BR + swapped dims.
        val decoded = IosImageDecoder.decode(oriented)
        assertEquals(baseH, decoded.width, "orientation-7 decode swaps width")
        assertEquals(baseW, decoded.height, "orientation-7 decode swaps height")
        assertEquals(Quad.BR, brightestQuadrant(decoded), "orientation 7 (transverse) → bottom-right")

        val out = IosFinalRenderSpine.renderAndEncode(
            oriented,
            IosRenderRequest(
                WaterMark.default.copy(text = "O", textSize = 10f, alpha = 0),
                UserPreferences(ImageFormat.JPEG, 90),
                0.5f,
                0.5f,
            ),
        )
        assertEquals(baseH, out.width, "oriented export width swapped")
        assertEquals(baseW, out.height, "oriented export height swapped")
        val outBmp = IosImageDecoder.decode(out.bytes)
        assertEquals(Quad.BR, brightestQuadrant(outBmp), "final export must keep upright orientation-7 quadrant")
        // Fresh JPEG encode must not retain Orientation tag APP1 from source.
        assertTrue(
            !out.bytes.asList().windowed(6).any { w ->
                w[0] == 0xFF.toByte() && w[1] == 0xE1.toByte() &&
                    w[4] == 'E'.code.toByte() && w[5] == 'x'.code.toByte()
            },
            "encoded output must strip source EXIF APP1",
        )
    }

    private enum class Quad { TL, TR, BL, BR }

    private fun brightestQuadrant(b: ImageBitmap): Quad {
        val px = b.toPixelMap()
        val mx = px.width / 2
        val my = px.height / 2
        val sums = DoubleArray(4)
        val counts = IntArray(4)
        for (y in 0 until px.height) {
            for (x in 0 until px.width) {
                val c = px[x, y]
                val lum = (c.red + c.green + c.blue).toDouble()
                val q = (if (y < my) 0 else 2) + (if (x < mx) 0 else 1)
                sums[q] += lum
                counts[q]++
            }
        }
        val means = DoubleArray(4) { if (counts[it] > 0) sums[it] / counts[it] else 0.0 }
        return when (means.indices.maxByOrNull { means[it] }!!) {
            0 -> Quad.TL
            1 -> Quad.TR
            2 -> Quad.BL
            else -> Quad.BR
        }
    }

    /** Minimal EXIF APP1 JPEG with Orientation=[orientation]; bright TOP-LEFT block in base pixels. */
    private fun jpegWithOrientation(orientation: Int, baseW: Int, baseH: Int): ByteArray {
        val bmp = ImageBitmap(baseW, baseH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bmp),
            Size(baseW.toFloat(), baseH.toFloat()),
        ) {
            drawRect(color = Color.Black)
            drawRect(color = Color.White, topLeft = Offset.Zero, size = Size(baseW / 2f, baseH / 2f))
        }
        val baseJpeg = SkiaImage.makeFromBitmap(bmp.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.JPEG)!!.bytes
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,
            0x00, orientation.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val exif = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        )
        val payload = exif + tiff
        val segLen = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            (segLen ushr 8).toByte(), (segLen and 0xFF).toByte(),
        ) + payload
        return byteArrayOf(baseJpeg[0], baseJpeg[1]) + app1 + baseJpeg.copyOfRange(2, baseJpeg.size)
    }

    /**
     * Mutation-resistant sRGB seam: working [ImageInfo] must report exact non-null [ColorSpace.sRGB].
     * Does **not** accept null as sRGB. Encoded container profile re-report is out of contract.
     */
    private fun assertExplicitSrgbWorkingSurface(alphaType: ColorAlphaType) {
        val info = IosFinalRenderSpine.explicitSrgbImageInfo(16, 12, alphaType)
        assertNotNull(info.colorSpace, "working surface colorSpace must not be null")
        assertEquals(ColorSpace.sRGB, info.colorSpace, "working surface must be exact sRGB")
        // Same factory product encode uses:
        val viaMake = ImageInfo.makeS32(16, 12, alphaType)
        assertEquals(ColorSpace.sRGB, viaMake.colorSpace)
    }
}
