@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package me.rosuh.easywatermark.render

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSMutableData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.numberWithInt
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.kCGImagePropertyOrientation

/**
 * Red→green proof that [IosImageDecoder] loads HEIF/HEIC via Apple ImageIO (direct pixels),
 * bounds native thumbnails, bakes orientation once, and keeps JPEG/PNG + failure contracts.
 *
 * HEIF fixtures are generated at runtime with ImageIO (`public.heic`) — no committed binary,
 * no personal device files.
 */
class IosImageDecoderHeifTest {

    private val baseW = 32
    private val baseH = 20

    @Test
    fun heif_fullDecode_preservesDimensionsAndContent() {
        val heif = requireHeifFixture(baseW, baseH, orientation = 1)
        assertTrue(IosImageIODecoder.looksLikeHeif(heif), "fixture must be recognized as HEIF")

        // Skia cannot decode the HEIF payloads that Photos stages; document the seam.
        val skiaRejected = runCatching { SkiaImage.makeFromEncoded(heif) }.isFailure
        assertTrue(skiaRejected, "precondition: Skia must not decode this HEIF fixture")

        val decoded = IosImageDecoder.decode(heif)
        assertEquals(baseW, decoded.width, "full HEIF decode width")
        assertEquals(baseH, decoded.height, "full HEIF decode height")
        assertEquals(Quad.TL, brightestQuadrant(decoded), "bright block stays top-left (orientation 1)")
    }

    @Test
    fun heif_thumbnail_respectsMaxEdgeWithoutFullResDecodeScaleOnly() {
        val heif = requireHeifFixture(baseW = 240, baseH = 160, orientation = 1)
        val thumb = IosImageDecoder.decodeThumbnail(heif, maxEdgePx = 48)
        val longest = maxOf(thumb.width, thumb.height)
        assertTrue(longest <= 48, "thumbnail longest edge must be ≤ 48, was $longest (${thumb.width}x${thumb.height})")
        assertTrue(thumb.width > 0 && thumb.height > 0)
        // Aspect preserved approximately (240:160 = 3:2)
        val ratio = thumb.width.toFloat() / thumb.height.toFloat()
        assertTrue(ratio in 1.3f..1.7f, "thumbnail aspect should stay near 1.5, was $ratio")
    }

    @Test
    fun heif_orientation6_bakesToUprightSwappedDims() {
        // Orientation 6 = 90° CW → upright dims swap; bright TL block moves to TR after bake.
        val heif = requireHeifFixture(baseW, baseH, orientation = 6)
        val upright = IosImageDecoder.decode(heif)
        assertEquals(baseH, upright.width, "orientation 6 swaps width")
        assertEquals(baseW, upright.height, "orientation 6 swaps height")
        assertEquals(Quad.TR, brightestQuadrant(upright), "orientation 6 moves bright block top-right")
    }

    @Test
    fun jpeg_and_png_stillDecodeViaSkiaPath() {
        val jpeg = solidJpeg(16, 12, Color.Red)
        val png = solidPng(16, 12, Color.Blue)
        val j = IosImageDecoder.decode(jpeg)
        val p = IosImageDecoder.decode(png)
        assertEquals(16, j.width)
        assertEquals(12, j.height)
        assertEquals(16, p.width)
        assertEquals(12, p.height)
        assertTrue(!IosImageIODecoder.looksLikeHeif(jpeg))
        assertTrue(!IosImageIODecoder.looksLikeHeif(png))
    }

    @Test
    fun corruptBytes_throwIllegalState_notCrash() {
        val ex = assertFailsWith<IllegalStateException> {
            IosImageDecoder.decode(byteArrayOf(1, 2, 3, 4, 5))
        }
        assertTrue(
            ex.message?.contains("could not decode", ignoreCase = true) == true ||
                ex.message?.contains("unsupported", ignoreCase = true) == true ||
                ex.message?.contains("ImageIO", ignoreCase = true) == true,
            "failure message should identify decode failure: ${ex.message}",
        )
    }

    @Test
    fun decodeThumbnail_nonPositiveMaxEdge_failsClosed() {
        val heif = requireHeifFixture(baseW, baseH, orientation = 1)
        val zero = assertFailsWith<IllegalStateException> {
            IosImageDecoder.decodeThumbnail(heif, maxEdgePx = 0)
        }
        assertTrue(
            zero.message?.contains("positive", ignoreCase = true) == true,
            "zero bound message: ${zero.message}",
        )
        val negative = assertFailsWith<IllegalStateException> {
            IosImageDecoder.decodeThumbnail(heif, maxEdgePx = -1)
        }
        assertTrue(
            negative.message?.contains("positive", ignoreCase = true) == true,
            "negative bound message: ${negative.message}",
        )
    }

    @Test
    fun heif_fullDecode_above4096_notCappedByUiLoader() {
        // Behavioral proof that full HEIF decode is not subject to a 4096 UI-loader cap.
        // Narrow strip keeps fixture encode/decode cheap while crossing the threshold.
        val wide = 4097
        val tall = 4
        val heif = requireHeifFixture(wide, tall, orientation = 1)
        val decoded = IosImageDecoder.decode(heif)
        assertEquals(wide, decoded.width, "full HEIF width must survive >4096")
        assertEquals(tall, decoded.height, "full HEIF height preserved")
    }

    // ---- HEIF fixture generation (ImageIO public.heic) ------------------------------------------

    /**
     * Build a tiny HEIF/HEIC with a bright top-left quadrant on a dark field.
     * [orientation] is the ImageIO / TIFF orientation tag (1..8) written into destination metadata.
     */
    private fun requireHeifFixture(baseW: Int, baseH: Int, orientation: Int): ByteArray {
        val cgImage = makeQuadrantCgImage(baseW, baseH)
            ?: fail("could not create CGImage fixture")
        try {
            val data = NSMutableData()
            val cfData = CFBridgingRetain(data)
                ?: fail("CFBridgingRetain(NSMutableData) failed")
            val uti = CFStringCreateWithCString(null, "public.heic", kCFStringEncodingUTF8)
            try {
                @Suppress("UNCHECKED_CAST")
                val dest = CGImageDestinationCreateWithData(
                    cfData as CFMutableDataRef,
                    uti,
                    1u,
                    null,
                )
                if (dest == null) {
                    // Simulator / host without HEIF encode — coordinator must decide; do not commit binaries.
                    fail(
                        "CGImageDestinationCreateWithData(public.heic) returned null — " +
                            "cannot generate HEIF fixture on this runtime; ask coordinator",
                    )
                }
                try {
                    val props = NSMutableDictionary()
                    // ImageIO orientation property string value is "Orientation".
                    setDict(props, "Orientation", NSNumber.numberWithInt(orientation))
                    // Also try the CF constant when it bridges cleanly.
                    runCatching {
                        setDict(props, kCGImagePropertyOrientation, NSNumber.numberWithInt(orientation))
                    }
                    val cfProps = CFBridgingRetain(props)
                        ?: fail("CFBridgingRetain(props) failed")
                    try {
                        @Suppress("UNCHECKED_CAST")
                        CGImageDestinationAddImage(
                            dest,
                            cgImage,
                            cfProps as CFDictionaryRef,
                        )
                    } finally {
                        CFBridgingRelease(cfProps)
                    }
                    if (!CGImageDestinationFinalize(dest)) {
                        fail("CGImageDestinationFinalize failed for public.heic")
                    }
                } finally {
                    CFRelease(dest)
                }
            } finally {
                if (uti != null) CFRelease(uti)
                CFBridgingRelease(cfData)
            }
            val bytes = IosByteArrayInterop.fromNSData(data)
            if (bytes.isEmpty() || !IosImageIODecoder.looksLikeHeif(bytes)) {
                fail("generated HEIF fixture empty or not recognized as HEIF (${bytes.size} bytes)")
            }
            return bytes
        } finally {
            CGImageRelease(cgImage)
        }
    }

    private fun makeQuadrantCgImage(width: Int, height: Int): CGImageRef? {
        // Draw in CG coordinates (origin bottom-left) so the bright block is visual top-left.
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return null
        val bitmapInfo =
            CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or
                kCGBitmapByteOrder32Little
        val ctx = CGBitmapContextCreate(
            data = null,
            width = width.convert(),
            height = height.convert(),
            bitsPerComponent = 8u,
            bytesPerRow = 0u,
            space = colorSpace,
            bitmapInfo = bitmapInfo,
        )
        CGColorSpaceRelease(colorSpace)
        if (ctx == null) return null
        return try {
            // Dark background.
            CGContextSetRGBFillColor(ctx, 0.0, 0.0, 0.0, 1.0)
            CGContextFillRect(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
            // Bright top-left quadrant in CG space: x=0..w/2, y=h/2..h.
            CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(
                ctx,
                CGRectMake(0.0, height / 2.0, width / 2.0, height / 2.0),
            )
            CGBitmapContextCreateImage(ctx)
        } finally {
            CGContextRelease(ctx)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setDict(dict: NSMutableDictionary, key: Any?, value: Any?) {
        if (key == null || value == null) return
        // Prefer plain String keys (known ImageIO property names) for reliable NSDictionary bridging.
        when (key) {
            is String -> dict.setObject(value, forKey = NSString.create(string = key))
            else -> dict.setObject(value, forKey = key as platform.Foundation.NSCopyingProtocol)
        }
    }

    private fun solidJpeg(w: Int, h: Int, color: Color): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            drawRect(color = color)
        }
        return SkiaImage.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(EncodedImageFormat.JPEG)!!.bytes
    }

    private fun solidPng(w: Int, h: Int, color: Color): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat())) {
            drawRect(color = color)
        }
        return SkiaImage.makeFromBitmap(bmp.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
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
                val lum = c.red + c.green + c.blue
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
}
