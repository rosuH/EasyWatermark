@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skia.impl.use
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import kotlin.time.TimeSource

/**
 * Shared CGImage → Skia transfer (ADR-0029 P2 extract).
 *
 * Algorithms moved unchanged from [IosImageIODecoder] (owned + legacy A/B arms).
 */
internal object IosCgImageBridge {
    private const val BYTES_PER_PIXEL = 4

    fun toSkiaImage(image: CGImageRef): SkiaImage {
        return when (IosCgImageTransferProbe.mode) {
            IosCgImageTransferMode.SkiaOwned -> toSkiaImageOwned(image)
            IosCgImageTransferMode.LegacyByteArray -> toSkiaImageLegacy(image)
        }
    }

    fun toSkiaBitmap(image: CGImageRef): SkiaBitmap {
        return when (IosCgImageTransferProbe.mode) {
            IosCgImageTransferMode.SkiaOwned -> toSkiaBitmapOwned(image)
            IosCgImageTransferMode.LegacyByteArray -> toSkiaBitmapLegacy(image)
        }
    }

    /** Phase-1 Image path: Skia `Data` owns the buffer; `makeRaster(Data)` does not copy. */
    fun toSkiaImageOwned(image: CGImageRef): SkiaImage {
        val (width, height, minRowBytes, byteCount) = bufferGeometry(image)
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
    fun toSkiaBitmapOwned(image: CGImageRef): SkiaBitmap {
        val (width, height, minRowBytes, byteCount) = bufferGeometry(image)
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
    fun toSkiaImageLegacy(image: CGImageRef): SkiaImage {
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
    fun toSkiaBitmapLegacy(image: CGImageRef): SkiaBitmap {
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

    private fun bufferGeometry(image: CGImageRef): BufferGeometry {
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
        val (width, height, rowBytes, _) = bufferGeometry(image)
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
