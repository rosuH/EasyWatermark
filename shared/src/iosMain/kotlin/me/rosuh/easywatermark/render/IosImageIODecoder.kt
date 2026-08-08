@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
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
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.numberWithInt
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.Foundation.NSLock

/** Oriented source dimensions read without a full pixel decode. */
internal data class IosImageMetadata(
    val width: Int,
    val height: Int,
    val orientation: Int,
)

/**
 * Path-first ImageIO edge for picker-owned sources.
 *
 * The input is an app-owned filesystem path, never a full `NSData -> ByteArray` bridge. Native
 * thumbnails ask ImageIO to subsample and bake EXIF orientation in one operation for JPEG, PNG,
 * HEIF, and every other ImageIO-supported picker format. Final export remains on its separate
 * full-resolution `IosFinalRenderSpine` path.
 */
internal object IosImageIODecoder {
    private const val BYTES_PER_PIXEL = 4

    fun metadata(sourcePath: String): IosImageMetadata = withSource(sourcePath) { source ->
        val index = CGImageSourceGetPrimaryImageIndex(source)
        val properties = CGImageSourceCopyPropertiesAtIndex(source, index, null)
            ?: error("IosImageIODecoder: metadata unavailable for '$sourcePath'")
        try {
            @Suppress("UNCHECKED_CAST")
            val dictionary = CFBridgingRelease(properties) as? NSDictionary
                ?: error("IosImageIODecoder: metadata bridge failed for '$sourcePath'")
            val width = propertyInt(dictionary, kCGImagePropertyPixelWidth, "PixelWidth")
            val height = propertyInt(dictionary, kCGImagePropertyPixelHeight, "PixelHeight")
            val orientation = propertyInt(dictionary, kCGImagePropertyOrientation, "Orientation")
                .takeIf { it in 1..8 } ?: 1
            val orientedWidth = if (orientation in 5..8) height else width
            val orientedHeight = if (orientation in 5..8) width else height
            require(orientedWidth > 0 && orientedHeight > 0) {
                "IosImageIODecoder: invalid metadata ${orientedWidth}x$orientedHeight for '$sourcePath'"
            }
            IosImageMetadata(orientedWidth, orientedHeight, orientation)
        } catch (t: Throwable) {
            // `CFBridgingRelease` transferred the properties reference; no CFRelease here.
            throw t
        }
    }

    /** Decode an orientation-aware ImageIO thumbnail bounded to [maxEdgePx]. */
    fun decodeThumbnail(sourcePath: String, maxEdgePx: Int): ImageBitmap {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        return withSource(sourcePath) { source ->
            val index = CGImageSourceGetPrimaryImageIndex(source)
            val cgImage = createThumbnail(source, index, maxEdgePx)
                ?: error("IosImageIODecoder: thumbnail failed for '$sourcePath' @ $maxEdgePx")
            IosImageIOOwnershipProbe.didCreateImage()
            try {
                IosImageIOOwnershipProbe.throwAfterCreateForTests?.invoke()
                cgImageToSkia(cgImage).toComposeImageBitmap()
            } finally {
                CGImageRelease(cgImage)
                IosImageIOOwnershipProbe.didReleaseImage()
            }
        }
    }

    private inline fun <T> withSource(sourcePath: String, block: (CGImageSourceRef) -> T): T {
        require(sourcePath.isNotBlank()) { "IosImageIODecoder: blank source path" }
        val url = NSURL.fileURLWithPath(sourcePath)
        val cfUrl = CFBridgingRetain(url)
            ?: error("IosImageIODecoder: URL bridge failed for '$sourcePath'")
        val source = try {
            @Suppress("UNCHECKED_CAST")
            CGImageSourceCreateWithURL(cfUrl as CFURLRef, null)
        } finally {
            CFBridgingRelease(cfUrl)
        } ?: error("IosImageIODecoder: unreadable/unsupported '$sourcePath'")
        IosImageIOOwnershipProbe.didCreateSource()
        try {
            return block(source)
        } finally {
            CFRelease(source)
            IosImageIOOwnershipProbe.didReleaseSource()
        }
    }

    private fun createThumbnail(
        source: CGImageSourceRef,
        index: ULong,
        maxEdgePx: Int,
    ): CGImageRef? {
        val options = NSMutableDictionary().apply {
            setObject(NSNumber.numberWithInt(1), forKey = NSString.create(string = "kCGImageSourceCreateThumbnailFromImageAlways"))
            setObject(NSNumber.numberWithInt(1), forKey = NSString.create(string = "kCGImageSourceCreateThumbnailWithTransform"))
            setObject(NSNumber.numberWithInt(0), forKey = NSString.create(string = "kCGImageSourceShouldCache"))
            setObject(NSNumber.numberWithInt(maxEdgePx), forKey = NSString.create(string = "kCGImageSourceThumbnailMaxPixelSize"))
        }
        val cfOptions = CFBridgingRetain(options)
            ?: error("IosImageIODecoder: options bridge failed")
        try {
            @Suppress("UNCHECKED_CAST")
            return CGImageSourceCreateThumbnailAtIndex(source, index, cfOptions as CFDictionaryRef)
        } finally {
            CFBridgingRelease(cfOptions)
        }
    }

    private fun propertyInt(dictionary: NSDictionary, key: Any?, fallback: String): Int {
        val value = dictionary.objectForKey(fallback)
            ?: key?.let { runCatching { dictionary.objectForKey(it) }.getOrNull() }
        return (value as? NSNumber)?.intValue ?: 0
    }

    /** Copy a +1 CGImage into an owned Skia raster; no retained native pixel buffer escapes. */
    private fun cgImageToSkia(image: CGImageRef): SkiaImage {
        val width = CGImageGetWidth(image).toLong()
        val height = CGImageGetHeight(image).toLong()
        require(width in 1..Int.MAX_VALUE && height in 1..Int.MAX_VALUE) {
            "IosImageIODecoder: unsupported pixel size ${width}x$height"
        }
        val rowBytes = width * BYTES_PER_PIXEL
        val byteCount = rowBytes * height
        require(rowBytes <= Int.MAX_VALUE && byteCount <= Int.MAX_VALUE) {
            "IosImageIODecoder: pixel buffer too large ${width}x$height"
        }
        val pixels = ByteArray(byteCount.toInt())
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
            ?: error("IosImageIODecoder: could not create sRGB color space")
        try {
            pixels.usePinned { pinned ->
                val bitmapInfo =
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.convert(),
                    height = height.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = rowBytes.convert(),
                    space = colorSpace,
                    bitmapInfo = bitmapInfo,
                ) ?: error("IosImageIODecoder: pixel context failed for ${width}x$height")
                try {
                    CGContextDrawImage(
                        context,
                        CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                        image,
                    )
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
        return SkiaImage.makeRaster(
            ImageInfo.makeS32(width.toInt(), height.toInt(), ColorAlphaType.PREMUL),
            pixels,
            rowBytes.toInt(),
        )
    }
}

/**
 * Test-visible ownership counters.  They are not production behavior: the actual release proof
 * is the `try/finally` surrounding every +1 ImageIO object above.
 */
internal object IosImageIOOwnershipProbe {
    private val lock = NSLock()
    private var sourcesCreated = 0
    private var sourcesReleased = 0
    private var imagesCreated = 0
    private var imagesReleased = 0
    var throwAfterCreateForTests: (() -> Unit)? = null

    data class Snapshot(
        val sourcesCreated: Int,
        val sourcesReleased: Int,
        val imagesCreated: Int,
        val imagesReleased: Int,
    )

    fun resetForTests() {
        lock.lock()
        try {
            sourcesCreated = 0
            sourcesReleased = 0
            imagesCreated = 0
            imagesReleased = 0
            throwAfterCreateForTests = null
        } finally {
            lock.unlock()
        }
    }

    fun snapshotForTests(): Snapshot {
        lock.lock()
        return try {
            Snapshot(sourcesCreated, sourcesReleased, imagesCreated, imagesReleased)
        } finally {
            lock.unlock()
        }
    }

    fun didCreateSource() = change { sourcesCreated += 1 }
    fun didReleaseSource() = change { sourcesReleased += 1 }
    fun didCreateImage() = change { imagesCreated += 1 }
    fun didReleaseImage() = change { imagesReleased += 1 }

    private inline fun change(block: IosImageIOOwnershipProbe.() -> Unit) {
        lock.lock()
        try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
