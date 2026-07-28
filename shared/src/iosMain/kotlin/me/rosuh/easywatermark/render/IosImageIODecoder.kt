@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package me.rosuh.easywatermark.render

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMake
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSDictionary
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.numberWithInt
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation

/**
 * Apple ImageIO decode edge for formats Skia cannot decode (notably HEIF/HEIC from Photos).
 *
 * Produces an owned premultiplied sRGB Skia raster by drawing the ImageIO [CGImageRef] into a
 * BGRA_8888 buffer — **no** JPEG/PNG re-encode intermediate. Orientation is baked once:
 * thumbnail path via `kCGImageSourceCreateThumbnailWithTransform`, full path via explicit EXIF bake.
 * Thumbnails honor `kCGImageSourceThumbnailMaxPixelSize` at the native decode request and **never**
 * fall back to full-resolution decode when a bound is requested.
 */
internal object IosImageIODecoder {

    private val heifBrands = setOf(
        "heic", "heif", "heix", "hevc", "hevx",
        "heim", "heis", "mif1", "msf1", "avic",
    )

    /** Bytes-per-pixel for the BGRA_8888 / N32 destination buffer. */
    private const val BYTES_PER_PIXEL = 4

    /** ISO-BMFF `ftyp` major/compatible brand check for HEIF family containers. */
    fun looksLikeHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[4] != 'f'.code.toByte() ||
            bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() ||
            bytes[7] != 'p'.code.toByte()
        ) {
            return false
        }
        if (brandAt(bytes, 8) in heifBrands) return true
        val boxSize = readBeU32(bytes, 0)
        val end = when {
            boxSize == 0 -> bytes.size
            boxSize > bytes.size -> bytes.size
            else -> boxSize
        }
        var off = 16
        while (off + 4 <= end) {
            if (brandAt(bytes, off) in heifBrands) return true
            off += 4
        }
        return false
    }

    /**
     * Decode via ImageIO.
     *
     * - [maxEdgePx] `null` → full primary image, orientation baked once (no UI-loader pixel cap).
     * - [maxEdgePx] positive → native orientation-aware thumbnail; failure is fail-closed (no full
     *   decode fallback).
     * - [maxEdgePx] ≤ 0 → explicit failure (invalid bound).
     */
    fun decodeToSkia(bytes: ByteArray, maxEdgePx: Int? = null): SkiaImage {
        if (bytes.isEmpty()) {
            error("IosImageIODecoder: empty image bytes")
        }
        if (maxEdgePx != null && maxEdgePx <= 0) {
            error("IosImageIODecoder: thumbnail maxEdgePx must be positive, was $maxEdgePx")
        }
        val nsData = IosByteArrayInterop.toNSData(bytes)
        // NSData ↔ CFData is toll-free; K/N requires an explicit bridge retain (not a Kotlin cast).
        val cfData = CFBridgingRetain(nsData)
            ?: error("IosImageIODecoder: CFBridgingRetain(NSData) failed")
        try {
            @Suppress("UNCHECKED_CAST")
            val source = CGImageSourceCreateWithData(cfData as CFDataRef, null)
                ?: error("IosImageIODecoder: CGImageSourceCreateWithData failed (${bytes.size} bytes)")
            try {
                val index = CGImageSourceGetPrimaryImageIndex(source)
                val cgImage = createOrientedImage(source, index, maxEdgePx)
                try {
                    return cgImageToSkia(cgImage)
                } finally {
                    CGImageRelease(cgImage)
                }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFBridgingRelease(cfData)
        }
    }

    /**
     * Returns a +1 retained upright [CGImageRef]. Throws [IllegalStateException] on failure
     * (including thumbnail miss when a bound was requested).
     */
    private fun createOrientedImage(
        source: CGImageSourceRef?,
        index: ULong,
        maxEdgePx: Int?,
    ): CGImageRef {
        if (maxEdgePx != null) {
            // Bound requested: fail closed. Never fall through to full-resolution decode.
            return createThumbnail(source, index, maxEdgePx)
                ?: error(
                    "IosImageIODecoder: ImageIO thumbnail failed at primary index $index " +
                        "(maxEdge=$maxEdgePx); refusing full-resolution fallback",
                )
        }

        // +1 retained by CreateImageAtIndex. Ownership:
        // - orientation 1 → transfer to caller (outer path still CGImageRelease's the return)
        // - bake success → return baked; always release raw here
        // - any throw from readOrientation/bakeOrientation/error → release raw in finally
        val raw = CGImageSourceCreateImageAtIndex(source, index, null)
            ?: error("IosImageIODecoder: CGImageSourceCreateImageAtIndex failed at index $index")
        var transferRawToCaller = false
        try {
            val orientation = readOrientation(source, index)
            if (orientation == 1) {
                transferRawToCaller = true
                return raw
            }
            return bakeOrientation(raw, orientation)
                ?: error(
                    "IosImageIODecoder: failed to bake orientation $orientation into upright pixels " +
                        "(allocation/context failure); refusing incorrectly oriented export",
                )
        } finally {
            if (!transferRawToCaller) {
                CGImageRelease(raw)
            }
        }
    }

    private fun createThumbnail(
        source: CGImageSourceRef?,
        index: ULong,
        maxEdgePx: Int,
    ): CGImageRef? {
        // Literal keys match ImageIO CFString constant values; avoids CFString→NSCopying cast issues.
        val nsOptions = nsDict(
            "kCGImageSourceCreateThumbnailFromImageAlways" to true,
            "kCGImageSourceCreateThumbnailWithTransform" to true,
            "kCGImageSourceShouldCache" to false,
            "kCGImageSourceThumbnailMaxPixelSize" to maxEdgePx,
        )
        val cfOptions = CFBridgingRetain(nsOptions) ?: return null
        try {
            @Suppress("UNCHECKED_CAST")
            return CGImageSourceCreateThumbnailAtIndex(source, index, cfOptions as CFDictionaryRef)
        } finally {
            CFBridgingRelease(cfOptions)
        }
    }

    private fun readOrientation(source: CGImageSourceRef?, index: ULong): Int {
        val propsRef = CGImageSourceCopyPropertiesAtIndex(source, index, null) ?: return 1
        @Suppress("UNCHECKED_CAST")
        val nsProps = CFBridgingRelease(propsRef) as? NSDictionary ?: return 1
        val value = nsProps.objectForKey("Orientation")
            ?: runCatching { nsProps.objectForKey(kCGImagePropertyOrientation as Any) }.getOrNull()
        val number = value as? NSNumber ?: return 1
        val o = number.intValue
        return if (o in 1..8) o else 1
    }

    /**
     * Bake EXIF/TIFF orientation into a new upright CGImage (+1 retain). Null if allocation fails.
     */
    private fun bakeOrientation(image: CGImageRef, orientation: Int): CGImageRef? {
        val (srcW, srcH) = checkedPixelSize(
            CGImageGetWidth(image),
            CGImageGetHeight(image),
            label = "bakeOrientation source",
        )
        val swap = orientation in setOf(5, 6, 7, 8)
        val dstW = if (swap) srcH else srcW
        val dstH = if (swap) srcW else srcH
        // Destination dims are either equal or swapped source dims already checked for Int fit.
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return null
        val bitmapInfo =
            CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
        val ctx = CGBitmapContextCreate(
            data = null,
            width = dstW.convert(),
            height = dstH.convert(),
            bitsPerComponent = 8u,
            bytesPerRow = 0u,
            space = colorSpace,
            bitmapInfo = bitmapInfo,
        )
        CGColorSpaceRelease(colorSpace)
        if (ctx == null) return null
        try {
            val t = orientationMatrix(orientation, srcW.toDouble(), srcH.toDouble())
            CGContextConcatCTM(ctx, t)
            CGContextDrawImage(
                ctx,
                CGRectMake(0.0, 0.0, srcW.toDouble(), srcH.toDouble()),
                image,
            )
            return CGBitmapContextCreateImage(ctx)
        } finally {
            CGContextRelease(ctx)
        }
    }

    /**
     * Standard EXIF orientation → CGAffineTransform mapping stored pixels to upright drawing.
     * Matrices match the common UIImage/ImageIO orientation bake table.
     */
    private fun orientationMatrix(
        orientation: Int,
        srcW: Double,
        srcH: Double,
    ) = when (orientation) {
        2 -> CGAffineTransformMake(-1.0, 0.0, 0.0, 1.0, srcW, 0.0)
        3 -> CGAffineTransformMake(-1.0, 0.0, 0.0, -1.0, srcW, srcH)
        4 -> CGAffineTransformMake(1.0, 0.0, 0.0, -1.0, 0.0, srcH)
        5 -> CGAffineTransformMake(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> CGAffineTransformMake(0.0, -1.0, 1.0, 0.0, 0.0, srcW)
        7 -> CGAffineTransformMake(0.0, -1.0, -1.0, 0.0, srcH, srcW)
        8 -> CGAffineTransformMake(0.0, 1.0, -1.0, 0.0, srcH, 0.0)
        else -> CGAffineTransformMake(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }

    private fun nsDict(vararg pairs: Pair<String, Any>): NSMutableDictionary {
        val dict = NSMutableDictionary()
        for ((k, v) in pairs) {
            val boxed: Any = when (v) {
                is Boolean -> if (v) NSNumber.numberWithInt(1) else NSNumber.numberWithInt(0)
                is Int -> NSNumber.numberWithInt(v)
                else -> v
            }
            dict.setObject(boxed, forKey = NSString.create(string = k))
        }
        return dict
    }

    /**
     * Draw [image] into an owned premultiplied BGRA (Skia N32 on Apple) sRGB buffer and wrap as
     * [SkiaImage]. Buffer sizing uses checked Long arithmetic so CoreGraphics never writes past
     * the Kotlin allocation.
     */
    private fun cgImageToSkia(image: CGImageRef): SkiaImage {
        val (width, height) = checkedPixelSize(
            CGImageGetWidth(image),
            CGImageGetHeight(image),
            label = "cgImageToSkia",
        )
        val rowBytesLong = width.toLong() * BYTES_PER_PIXEL
        val totalBytesLong = rowBytesLong * height.toLong()
        if (rowBytesLong > Int.MAX_VALUE || totalBytesLong > Int.MAX_VALUE) {
            error(
                "IosImageIODecoder: pixel buffer not representable as Int-sized ByteArray " +
                    "(${width}x$height, rowBytes=$rowBytesLong, total=$totalBytesLong)",
            )
        }
        val rowBytes = rowBytesLong.toInt()
        val totalBytes = totalBytesLong.toInt()
        val pixels = ByteArray(totalBytes)
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
            ?: error("IosImageIODecoder: CGColorSpaceCreateWithName(sRGB) failed")
        try {
            pixels.usePinned { pinned ->
                val bitmapInfo =
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or
                        kCGBitmapByteOrder32Little
                val ctx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.convert(),
                    height = height.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = rowBytes.convert(),
                    space = colorSpace,
                    bitmapInfo = bitmapInfo,
                ) ?: error("IosImageIODecoder: CGBitmapContextCreate failed (${width}x$height)")
                try {
                    // No extra Y flip: ImageIO CGImages draw upright into this top-down buffer layout.
                    CGContextDrawImage(
                        ctx,
                        CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                        image,
                    )
                } finally {
                    CGContextRelease(ctx)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
        val info = ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)
        return SkiaImage.makeRaster(info, pixels, rowBytes)
    }

    /**
     * Validate native `size_t` dimensions before Int conversion and 4-byte row arithmetic.
     * Fails closed on zero or values that cannot be represented by Kotlin/Skia Int APIs.
     * Not a quality/size product cap — only overflow/representability.
     */
    private fun checkedPixelSize(
        nativeWidth: ULong,
        nativeHeight: ULong,
        label: String,
    ): Pair<Int, Int> {
        if (nativeWidth == 0uL || nativeHeight == 0uL) {
            error("IosImageIODecoder: $label invalid zero size ${nativeWidth}x$nativeHeight")
        }
        val maxInt = Int.MAX_VALUE.toULong()
        if (nativeWidth > maxInt || nativeHeight > maxInt) {
            error(
                "IosImageIODecoder: $label dimensions exceed Int range " +
                    "(${nativeWidth}x$nativeHeight)",
            )
        }
        // Ensure width * 4 * height fits in a signed 64-bit total before Int total check.
        val rowBytes = nativeWidth * BYTES_PER_PIXEL.toULong()
        if (rowBytes > maxInt) {
            error(
                "IosImageIODecoder: $label rowBytes exceeds Int " +
                    "(width=$nativeWidth, rowBytes=$rowBytes)",
            )
        }
        // Use ULong multiply carefully: if height is large, product may wrap ULong — check via division.
        if (nativeHeight > 0uL && rowBytes > ULong.MAX_VALUE / nativeHeight) {
            error(
                "IosImageIODecoder: $label total byte size overflows ULong " +
                    "(${nativeWidth}x$nativeHeight)",
            )
        }
        val total = rowBytes * nativeHeight
        if (total > maxInt) {
            error(
                "IosImageIODecoder: $label total bytes exceed Int ByteArray limit " +
                    "(${nativeWidth}x$nativeHeight, total=$total)",
            )
        }
        return nativeWidth.toInt() to nativeHeight.toInt()
    }

    private fun brandAt(bytes: ByteArray, offset: Int): String {
        if (offset + 4 > bytes.size) return ""
        return bytes.copyOfRange(offset, offset + 4).decodeToString()
    }

    private fun readBeU32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
