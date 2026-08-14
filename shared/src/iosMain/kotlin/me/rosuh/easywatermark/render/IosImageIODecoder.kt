@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.use
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
import platform.CoreFoundation.CFDataRef
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.Foundation.NSLock
import kotlin.time.TimeSource

/** Oriented source dimensions read without a full pixel decode. */
internal data class IosImageMetadata(
    val width: Int,
    val height: Int,
    val orientation: Int,
)

/** One [CGImageSource] open: oriented metadata + Skia bitmap Coil can wrap. */
internal data class IosThumbnailBitmap(
    val metadata: IosImageMetadata,
    val bitmap: SkiaBitmap,
)

/**
 * Path-first ImageIO edge for picker-owned sources.
 *
 * Path-first for picker-owned files; bytes overload exists for [IosImageDecoder] HEIF
 * (export/in-memory). Native thumbnails subsample and bake EXIF in one operation. Final export
 * still goes through [IosFinalRenderSpine], which now uses this ImageIO HEIF path instead of
 * UIImage→JPEG.
 */
internal object IosImageIODecoder {
    private const val BYTES_PER_PIXEL = 4

    /** Sentinel for "omit `kCGImageSourceSubsampleFactor`". */
    const val NO_SUBSAMPLE: Int = 1

    /** The only factors `kCGImageSourceSubsampleFactor` accepts, largest first. */
    private val SUBSAMPLE_FACTORS = intArrayOf(8, 4, 2)

    /**
     * Largest documented subsample factor whose subsampled long edge is still at least
     * [maxEdgePx], or [NO_SUBSAMPLE].
     *
     * `kCGImageSourceCreateThumbnailFromImageAlways` decodes the **full** image and then scales,
     * which is why a 128 px request on a 12MP HEIC is not cheaper than a 1920 px one — measured
     * order-balanced on an iPhone 16 Pro it is actually *more* expensive, since it pays the same
     * decode plus a heavier downsample. Subsampling moves that reduction into the decoder.
     *
     * Choosing the factor from the source's own long edge is what keeps this quality-safe: the
     * subsampled image never drops below the requested thumbnail size, so
     * `kCGImageSourceThumbnailMaxPixelSize` still produces identical output dimensions and never
     * upscales. If a codec cannot honor the factor, ImageIO returns a *larger* or full-size image,
     * so the worst case is no speedup rather than a soft thumbnail.
     */
    fun subsampleFactorFor(sourceLongEdgePx: Int, maxEdgePx: Int): Int {
        if (sourceLongEdgePx <= 0 || maxEdgePx <= 0) return NO_SUBSAMPLE
        return SUBSAMPLE_FACTORS.firstOrNull { sourceLongEdgePx / it >= maxEdgePx }
            ?: NO_SUBSAMPLE
    }

    fun metadata(sourcePath: String): IosImageMetadata = withUrlSource(sourcePath) { source ->
        readMetadata(source, sourcePath)
    }

    fun metadataFromBytes(bytes: ByteArray): IosImageMetadata = withDataSource(bytes) { source ->
        readMetadata(source, "bytes(${bytes.size})")
    }

    /**
     * Decode an orientation-aware ImageIO thumbnail bounded to [maxEdgePx].
     *
     * Production ([IosCgImageTransferMode.SkiaOwned]): Skia-owned bitmap +
     * [asComposeImageBitmap] (L2/L3 = 0). Legacy A/B arm still pays `Image` +
     * [toComposeImageBitmap] so the Compose re-raster cost stays measurable.
     */
    fun decodeThumbnail(
        sourcePath: String,
        maxEdgePx: Int,
        allowSubsample: Boolean = false,
    ): ImageBitmap {
        return when (IosCgImageTransferProbe.mode) {
            IosCgImageTransferMode.SkiaOwned -> {
                val bitmap = decodeThumbnailBitmap(
                    sourcePath,
                    maxEdgePx,
                    allowSubsample = allowSubsample,
                )
                val mark = TimeSource.Monotonic.markNow()
                val composed = bitmap.asComposeImageBitmap()
                IosCgImageTransferProbe.lastOrNull()?.let { prior ->
                    IosCgImageTransferProbe.record(
                        prior.copy(
                            surface = "compose",
                            handoffNs = prior.handoffNs + mark.elapsedNow().inWholeNanoseconds,
                            accountedAllocBytes = prior.frameBytes,
                            fullFrameWrites = 1,
                        ),
                    )
                }
                composed
            }
            IosCgImageTransferMode.LegacyByteArray -> {
                val image = decodeThumbnailSkia(
                    sourcePath,
                    maxEdgePx,
                    allowSubsample = allowSubsample,
                )
                val mark = TimeSource.Monotonic.markNow()
                val composed = image.toComposeImageBitmap()
                val composeNs = mark.elapsedNow().inWholeNanoseconds
                IosCgImageTransferProbe.lastOrNull()?.let { prior ->
                    IosCgImageTransferProbe.record(
                        prior.copy(
                            surface = "compose",
                            handoffNs = prior.handoffNs + composeNs,
                            accountedAllocBytes = prior.frameBytes * 3,
                            fullFrameWrites = 3,
                        ),
                    )
                }
                composed
            }
        }
    }

    /**
     * Same ImageIO thumbnail as [decodeThumbnail], stopping at an owned Skia [SkiaBitmap].
     * Prefer this (or [asComposeImageBitmap]) over [decodeThumbnailSkia] when the consumer
     * needs pixels for Compose/Coil rather than an `org.jetbrains.skia.Image`.
     */
    fun decodeThumbnailBitmap(
        sourcePath: String,
        maxEdgePx: Int,
        shouldCache: Boolean = false,
        allowSubsample: Boolean = false,
    ): SkiaBitmap {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        return withUrlSource(sourcePath) { source ->
            val subsampleFactor = if (allowSubsample) {
                val metadata = readMetadata(source, sourcePath)
                subsampleFactorFor(maxOf(metadata.width, metadata.height), maxEdgePx)
            } else {
                NO_SUBSAMPLE
            }
            decodeThumbnailBitmapFromSource(
                source = source,
                maxEdgePx = maxEdgePx,
                shouldCache = shouldCache,
                label = sourcePath,
                subsampleFactor = subsampleFactor,
            )
        }
    }

    /**
     * Same ImageIO thumbnail as [decodeThumbnail], but stop at Skia `Image`
     * (no Compose [ImageBitmap] wrap). Uses a Skia-owned `Data` buffer — no Kotlin
     * `ByteArray` mid-copy.
     */
    fun decodeThumbnailSkia(
        sourcePath: String,
        maxEdgePx: Int,
        shouldCache: Boolean = false,
        allowSubsample: Boolean = false,
    ): SkiaImage {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        return withUrlSource(sourcePath) { source ->
            decodeThumbnailFromSource(source, maxEdgePx, shouldCache, sourcePath, allowSubsample)
        }
    }

    /**
     * Size + thumbnail from **one** `CGImageSource` open. Coil owns the returned bitmap;
     * do not close it after [org.jetbrains.skia.Bitmap] is wrapped as a Coil Image.
     */
    fun decodeThumbnailBitmapWithMetadata(
        sourcePath: String,
        maxEdgePx: Int,
        shouldCache: Boolean = false,
        allowSubsample: Boolean = false,
    ): IosThumbnailBitmap {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        return withUrlSource(sourcePath) { source ->
            val metadata = readMetadata(source, sourcePath)
            val bitmap = decodeThumbnailBitmapFromSource(
                source = source,
                maxEdgePx = maxEdgePx,
                shouldCache = shouldCache,
                label = sourcePath,
                subsampleFactor = if (allowSubsample) {
                    subsampleFactorFor(maxOf(metadata.width, metadata.height), maxEdgePx)
                } else {
                    NO_SUBSAMPLE
                },
            )
            IosThumbnailBitmap(metadata, bitmap)
        }
    }

    /**
     * Full-res HEIF as an owned Skia bitmap (no Compose re-raster). Prefer
     * [asComposeImageBitmap] at ImageBitmap call sites.
     */
    fun decodePrimaryBitmapFromBytes(
        bytes: ByteArray,
        shouldCache: Boolean = false,
    ): SkiaBitmap {
        require(bytes.isNotEmpty()) { "IosImageIODecoder: empty image bytes" }
        return withDataSource(bytes) { source ->
            val label = "bytes(${bytes.size})"
            val metadata = readMetadata(source, label)
            val edge = maxOf(metadata.width, metadata.height).coerceAtLeast(1)
            decodeThumbnailBitmapFromSource(source, edge, shouldCache, label)
        }
    }

    /**
     * In-memory thumbnail as owned Skia bitmap (export / [IosImageDecoder] HEIF).
     */
    fun decodeThumbnailBitmapFromBytes(
        bytes: ByteArray,
        maxEdgePx: Int,
        shouldCache: Boolean = false,
    ): SkiaBitmap {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        require(bytes.isNotEmpty()) { "IosImageIODecoder: empty image bytes" }
        return withDataSource(bytes) { source ->
            decodeThumbnailBitmapFromSource(
                source,
                maxEdgePx,
                shouldCache,
                "bytes(${bytes.size})",
            )
        }
    }

    /**
     * Metadata + thumbnail Image from one in-memory source (export / [IosImageDecoder] HEIF).
     */
    fun decodeThumbnailSkiaWithMetadataFromBytes(
        bytes: ByteArray,
        maxEdgePx: Int,
        shouldCache: Boolean = false,
    ): Pair<IosImageMetadata, SkiaImage> {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        require(bytes.isNotEmpty()) { "IosImageIODecoder: empty image bytes" }
        return withDataSource(bytes) { source ->
            val label = "bytes(${bytes.size})"
            val metadata = readMetadata(source, label)
            val image = decodeThumbnailFromSource(source, maxEdgePx, shouldCache, label)
            metadata to image
        }
    }

    /** Full-res HEIF (thumbnail max = source long edge) from one `CGImageSource`. */
    fun decodePrimarySkiaFromBytes(
        bytes: ByteArray,
        shouldCache: Boolean = false,
    ): SkiaImage {
        require(bytes.isNotEmpty()) { "IosImageIODecoder: empty image bytes" }
        return withDataSource(bytes) { source ->
            val label = "bytes(${bytes.size})"
            val metadata = readMetadata(source, label)
            val edge = maxOf(metadata.width, metadata.height).coerceAtLeast(1)
            decodeThumbnailFromSource(source, edge, shouldCache, label)
        }
    }

    /**
     * Same ImageIO thumbnail from in-memory bytes (export / [IosImageDecoder] HEIF).
     * Prefer the path overload for picker-owned files.
     */
    fun decodeThumbnailSkiaFromBytes(
        bytes: ByteArray,
        maxEdgePx: Int,
        shouldCache: Boolean = false,
    ): SkiaImage {
        require(maxEdgePx > 0) { "IosImageIODecoder: thumbnail max edge must be positive" }
        require(bytes.isNotEmpty()) { "IosImageIODecoder: empty image bytes" }
        return withDataSource(bytes) { source ->
            decodeThumbnailFromSource(source, maxEdgePx, shouldCache, "bytes(${bytes.size})")
        }
    }

    private fun readMetadata(source: CGImageSourceRef, label: String): IosImageMetadata {
        val index = CGImageSourceGetPrimaryImageIndex(source)
        val properties = CGImageSourceCopyPropertiesAtIndex(source, index, null)
            ?: error("IosImageIODecoder: metadata unavailable for '$label'")
        try {
            @Suppress("UNCHECKED_CAST")
            val dictionary = CFBridgingRelease(properties) as? NSDictionary
                ?: error("IosImageIODecoder: metadata bridge failed for '$label'")
            val width = propertyInt(dictionary, kCGImagePropertyPixelWidth, "PixelWidth")
            val height = propertyInt(dictionary, kCGImagePropertyPixelHeight, "PixelHeight")
            val orientation = propertyInt(dictionary, kCGImagePropertyOrientation, "Orientation")
                .takeIf { it in 1..8 } ?: 1
            val orientedWidth = if (orientation in 5..8) height else width
            val orientedHeight = if (orientation in 5..8) width else height
            require(orientedWidth > 0 && orientedHeight > 0) {
                "IosImageIODecoder: invalid metadata ${orientedWidth}x$orientedHeight for '$label'"
            }
            return IosImageMetadata(orientedWidth, orientedHeight, orientation)
        } catch (t: Throwable) {
            // `CFBridgingRelease` transferred the properties reference; no CFRelease here.
            throw t
        }
    }

    private fun decodeThumbnailFromSource(
        source: CGImageSourceRef,
        maxEdgePx: Int,
        shouldCache: Boolean,
        label: String,
        allowSubsample: Boolean = false,
    ): SkiaImage {
        val index = CGImageSourceGetPrimaryImageIndex(source)
        val subsampleFactor = if (allowSubsample) {
            // Properties-only read: no pixel decode, same open.
            val metadata = readMetadata(source, label)
            subsampleFactorFor(maxOf(metadata.width, metadata.height), maxEdgePx)
        } else {
            NO_SUBSAMPLE
        }
        val cgImage = createThumbnail(source, index, maxEdgePx, shouldCache, subsampleFactor)
            ?: error("IosImageIODecoder: thumbnail failed for '$label' @ $maxEdgePx")
        IosImageIOOwnershipProbe.didCreateImage()
        try {
            IosImageIOOwnershipProbe.throwAfterCreateForTests?.invoke()
            return cgImageToSkia(cgImage)
        } finally {
            CGImageRelease(cgImage)
            IosImageIOOwnershipProbe.didReleaseImage()
        }
    }

    private fun decodeThumbnailBitmapFromSource(
        source: CGImageSourceRef,
        maxEdgePx: Int,
        shouldCache: Boolean,
        label: String,
        subsampleFactor: Int = NO_SUBSAMPLE,
    ): SkiaBitmap {
        val index = CGImageSourceGetPrimaryImageIndex(source)
        val cgImage = createThumbnail(source, index, maxEdgePx, shouldCache, subsampleFactor)
            ?: error("IosImageIODecoder: thumbnail failed for '$label' @ $maxEdgePx")
        IosImageIOOwnershipProbe.didCreateImage()
        try {
            IosImageIOOwnershipProbe.throwAfterCreateForTests?.invoke()
            return cgImageToSkiaBitmap(cgImage)
        } finally {
            CGImageRelease(cgImage)
            IosImageIOOwnershipProbe.didReleaseImage()
        }
    }

    private inline fun <T> withUrlSource(sourcePath: String, block: (CGImageSourceRef) -> T): T {
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
        return withOwnedSource(source, block)
    }

    private inline fun <T> withDataSource(bytes: ByteArray, block: (CGImageSourceRef) -> T): T {
        val nsData = IosByteArrayInterop.toNSData(bytes)
        val cfData = CFBridgingRetain(nsData)
            ?: error("IosImageIODecoder: data bridge failed (${bytes.size} bytes)")
        val source = try {
            @Suppress("UNCHECKED_CAST")
            CGImageSourceCreateWithData(cfData as CFDataRef, null)
        } finally {
            CFBridgingRelease(cfData)
        } ?: error("IosImageIODecoder: unreadable/unsupported ${bytes.size}-byte image")
        return withOwnedSource(source, block)
    }

    private inline fun <T> withOwnedSource(source: CGImageSourceRef, block: (CGImageSourceRef) -> T): T {
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
        shouldCache: Boolean = false,
        subsampleFactor: Int = NO_SUBSAMPLE,
    ): CGImageRef? {
        val options = NSMutableDictionary().apply {
            // ...FromImageAlways (not ...IfAbsent): IfAbsent returns whatever embedded thumbnail
            // the file happens to carry, at an unpredictable and possibly tiny size.
            setObject(NSNumber.numberWithInt(1), forKey = NSString.create(string = "kCGImageSourceCreateThumbnailFromImageAlways"))
            setObject(NSNumber.numberWithInt(1), forKey = NSString.create(string = "kCGImageSourceCreateThumbnailWithTransform"))
            setObject(
                NSNumber.numberWithInt(if (shouldCache) 1 else 0),
                forKey = NSString.create(string = "kCGImageSourceShouldCache"),
            )
            setObject(NSNumber.numberWithInt(maxEdgePx), forKey = NSString.create(string = "kCGImageSourceThumbnailMaxPixelSize"))
            if (subsampleFactor > NO_SUBSAMPLE) {
                setObject(
                    NSNumber.numberWithInt(subsampleFactor),
                    forKey = NSString.create(string = "kCGImageSourceSubsampleFactor"),
                )
            }
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

    private fun cgImageToSkia(image: CGImageRef): SkiaImage {
        return when (IosCgImageTransferProbe.mode) {
            IosCgImageTransferMode.SkiaOwned -> cgImageToSkiaOwned(image)
            IosCgImageTransferMode.LegacyByteArray -> cgImageToSkiaLegacy(image)
        }
    }

    private fun cgImageToSkiaBitmap(image: CGImageRef): SkiaBitmap {
        return when (IosCgImageTransferProbe.mode) {
            IosCgImageTransferMode.SkiaOwned -> cgImageToSkiaBitmapOwned(image)
            IosCgImageTransferMode.LegacyByteArray -> cgImageToSkiaBitmapLegacy(image)
        }
    }

    /** Phase-1 Image path: Skia `Data` owns the buffer; `makeRaster(Data)` does not copy. */
    private fun cgImageToSkiaOwned(image: CGImageRef): SkiaImage {
        val (width, height, minRowBytes, byteCount) = cgImageBufferGeometry(image)
        val info = ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)
        val data = SkiaData.makeUninitialized(byteCount)
        val drawNs = drawCgImageIntoSkiaBuffer(
            pixels = data.writableData(),
            width = width,
            height = height,
            rowBytes = minRowBytes,
            image = image,
        )
        val handoffMark = TimeSource.Monotonic.markNow()
        val skia = SkiaImage.makeRaster(info, data, minRowBytes)
        recordTransfer(
            surface = "image",
            width = width,
            height = height,
            frameBytes = byteCount,
            accountedAllocBytes = byteCount,
            fullFrameWrites = 1,
            drawNs = drawNs,
            handoffNs = handoffMark.elapsedNow().inWholeNanoseconds,
        )
        return skia
    }

    /** Phase-1 Bitmap / Coil path. */
    private fun cgImageToSkiaBitmapOwned(image: CGImageRef): SkiaBitmap {
        val (width, height, minRowBytes, byteCount) = cgImageBufferGeometry(image)
        val info = ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)
        val bitmap = SkiaBitmap()
        if (!bitmap.allocPixels(info, minRowBytes)) {
            error("IosImageIODecoder: allocPixels failed for ${width}x$height")
        }
        val pixmap = bitmap.peekPixels()
            ?: error("IosImageIODecoder: peekPixels failed for ${width}x$height")
        val drawNs = pixmap.use { pixels ->
            drawCgImageIntoSkiaBuffer(
                pixels = pixels.addr,
                width = width,
                height = height,
                rowBytes = pixels.rowBytes,
                image = image,
            )
        }
        val handoffMark = TimeSource.Monotonic.markNow()
        bitmap.setImmutable()
        recordTransfer(
            surface = "bitmap",
            width = width,
            height = height,
            frameBytes = byteCount,
            accountedAllocBytes = byteCount,
            fullFrameWrites = 1,
            drawNs = drawNs,
            handoffNs = handoffMark.elapsedNow().inWholeNanoseconds,
        )
        return bitmap
    }

    /** Pre-Phase-1 Image path (A/B arm A). */
    private fun cgImageToSkiaLegacy(image: CGImageRef): SkiaImage {
        val (pixels, width, height, rowBytes, drawNs) = copyCgImagePixelsLegacy(image)
        val handoffMark = TimeSource.Monotonic.markNow()
        val skia = SkiaImage.makeRaster(
            ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL),
            pixels,
            rowBytes,
        )
        val frameBytes = rowBytes * height
        recordTransfer(
            surface = "image",
            width = width,
            height = height,
            frameBytes = frameBytes,
            accountedAllocBytes = frameBytes * 2,
            fullFrameWrites = 2,
            drawNs = drawNs,
            handoffNs = handoffMark.elapsedNow().inWholeNanoseconds,
        )
        return skia
    }

    /** Pre-Phase-1 Bitmap path (A/B arm A). */
    private fun cgImageToSkiaBitmapLegacy(image: CGImageRef): SkiaBitmap {
        val (pixels, width, height, rowBytes, drawNs) = copyCgImagePixelsLegacy(image)
        val handoffMark = TimeSource.Monotonic.markNow()
        val bitmap = SkiaBitmap()
        val info = ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)
        if (!bitmap.installPixels(info, pixels, rowBytes)) {
            error("IosImageIODecoder: installPixels failed for ${width}x$height")
        }
        bitmap.setImmutable()
        val frameBytes = rowBytes * height
        recordTransfer(
            surface = "bitmap",
            width = width,
            height = height,
            frameBytes = frameBytes,
            accountedAllocBytes = frameBytes * 2,
            fullFrameWrites = 2,
            drawNs = drawNs,
            handoffNs = handoffMark.elapsedNow().inWholeNanoseconds,
        )
        return bitmap
    }

    private data class BufferGeometry(
        val width: Int,
        val height: Int,
        val rowBytes: Int,
        val byteCount: Int,
    )

    private data class LegacyPixels(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val rowBytes: Int,
        val drawNs: Long,
    )

    private fun cgImageBufferGeometry(image: CGImageRef): BufferGeometry {
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
        return BufferGeometry(width.toInt(), height.toInt(), rowBytes.toInt(), byteCount.toInt())
    }

    private fun copyCgImagePixelsLegacy(image: CGImageRef): LegacyPixels {
        val (width, height, rowBytes, _) = cgImageBufferGeometry(image)
        val pixels = ByteArray(rowBytes * height)
        val drawNs = pixels.usePinned { pinned ->
            drawCgImageIntoAddress(
                dataPtr = pinned.addressOf(0),
                width = width,
                height = height,
                rowBytes = rowBytes,
                image = image,
            )
        }
        return LegacyPixels(pixels, width, height, rowBytes, drawNs)
    }

    private fun drawCgImageIntoSkiaBuffer(
        pixels: NativePointer,
        width: Int,
        height: Int,
        rowBytes: Int,
        image: CGImageRef,
    ): Long {
        val dataPtr = interpretCPointer<ByteVar>(pixels)
            ?: error("IosImageIODecoder: null Skia pixel pointer for ${width}x$height")
        return drawCgImageIntoAddress(dataPtr, width, height, rowBytes, image)
    }

    private fun drawCgImageIntoAddress(
        dataPtr: CPointer<ByteVar>,
        width: Int,
        height: Int,
        rowBytes: Int,
        image: CGImageRef,
    ): Long {
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
            ?: error("IosImageIODecoder: could not create sRGB color space")
        val mark = TimeSource.Monotonic.markNow()
        try {
            val bitmapInfo =
                CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value or kCGBitmapByteOrder32Little
            val context = CGBitmapContextCreate(
                data = dataPtr,
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
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
        return mark.elapsedNow().inWholeNanoseconds
    }

    private fun recordTransfer(
        surface: String,
        width: Int,
        height: Int,
        frameBytes: Int,
        accountedAllocBytes: Int,
        fullFrameWrites: Int,
        drawNs: Long,
        handoffNs: Long,
    ) {
        IosCgImageTransferProbe.record(
            IosCgImageTransferSample(
                mode = IosCgImageTransferProbe.mode,
                surface = surface,
                width = width,
                height = height,
                frameBytes = frameBytes,
                accountedAllocBytes = accountedAllocBytes,
                fullFrameWrites = fullFrameWrites,
                drawNs = drawNs,
                handoffNs = handoffNs,
            ),
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
