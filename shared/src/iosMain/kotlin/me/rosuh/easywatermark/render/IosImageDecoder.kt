@file:OptIn(ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * The **iOS platform image-decode boundary** — the iOS analogue of Desktop [DesktopImageDecoder].
 * Decodes a real encoded image (PNG/JPEG/HEIC/…) into a Compose [ImageBitmap] for product paint.
 * C3 Final Export ([IosFinalRenderSpine]) and Preview ([IosPreviewRaster]) call full-res
 * [decode] / [decodeThumbnail] here, then compose via [CommonWatermarkPipeline]; never re-rotate
 * after a successful decode (Skia bakes EXIF for JPEG/PNG; ImageIO bakes HEIF orientation).
 *
 * Primary path is **Skia** (`Image.makeFromEncoded`) — no extra dependency. Skia covers JPEG/PNG/WebP
 * well but **does not decode HEIC/HEIF** (common from iPhone Photos with
 * `preferredItemEncoding = .current`). HEIF uses **ImageIO** (`IosImageIODecoder` — same native
 * thumbnail + EXIF bake as the Coil HEIF decoder), not a
 * UIImage→JPEG transcode. UIImage remains a last-resort fallback for other platform-only codecs.
 *
 * ## EXIF / orientation
 * - Skia path: `makeFromEncoded` already bakes JPEG EXIF orientation (see `IosExifOrientationTest`).
 * - ImageIO HEIF: `CGImageSourceCreateThumbnailAtIndex` + `CreateThumbnailWithTransform`.
 * - UIImage fallback: draw into a graphics context at `UIImage.size` so `imageOrientation` is baked.
 */
/** J5: decode edge — not called from Swift (goes through bridges). */
internal object IosImageDecoder {

    /**
     * Decode encoded image [bytes] into an [ImageBitmap]. Throws [IllegalStateException] if neither
     * Skia nor UIImage can decode so callers fail loudly instead of propagating a bad image.
     */
    fun decode(bytes: ByteArray): ImageBitmap {
        val skiaImage = decodeSkia(bytes)
        return skiaImage.toComposeImageBitmap()
    }

    /**
     * Decode and downscale so the longer edge is at most [maxEdgePx]. Used for filmstrip cells
     * (≈40dp) so multi-pick does not decode multi-megapixel bitmaps for every thumbnail.
     */
    fun decodeThumbnail(bytes: ByteArray, maxEdgePx: Int = 160): ImageBitmap {
        if (looksLikeHeif(bytes)) {
            runCatching {
                IosImageIODecoder.decodeThumbnailSkiaFromBytes(bytes, maxEdgePx)
                    .toComposeImageBitmap()
            }.getOrNull()?.let { return it }
        }
        return scaleSkia(decodeSkia(bytes), maxEdgePx).toComposeImageBitmap()
    }

    /**
     * Re-encode [bytes] as PNG with longest edge ≤ [maxEdgePx] for **on-screen preview export**.
     * Full-res camera photos (12MP+) make Skiko watermark raster multi-second; preview does not
     * need full resolution.
     */
    fun downscaleEncodedToPng(bytes: ByteArray, maxEdgePx: Int = 1600): ByteArray {
        val scaled = scaleSkia(decodeSkia(bytes), maxEdgePx)
        val data = scaled.encodeToData(EncodedImageFormat.PNG)
            ?: error("IosImageDecoder: failed to encode downscaled PNG")
        return data.bytes
    }

    private fun scaleSkia(skiaImage: SkiaImage, maxEdgePx: Int): SkiaImage {
        val w = skiaImage.width
        val h = skiaImage.height
        val longest = maxOf(w, h).coerceAtLeast(1)
        if (longest <= maxEdgePx) {
            return skiaImage
        }
        val scale = maxEdgePx.toFloat() / longest.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(nw, nh)
        surface.canvas.drawImageRect(
            skiaImage,
            src = SkiaRect.makeWH(w.toFloat(), h.toFloat()),
            dst = SkiaRect.makeWH(nw.toFloat(), nh.toFloat()),
            samplingMode = SamplingMode.LINEAR,
            paint = null,
            strict = true,
        )
        return surface.makeImageSnapshot()
    }

    private fun decodeSkia(bytes: ByteArray): SkiaImage {
        if (looksLikeHeif(bytes)) {
            decodeHeifViaImageIO(bytes)?.let { return it }
        } else {
            try {
                return SkiaImage.makeFromEncoded(bytes)
            } catch (_: Throwable) {
                // Fall through to UIImage for other platform-only codecs or rare Skia gaps.
            }
        }
        val viaUi = decodeViaUIImage(bytes)
        if (viaUi != null) {
            return viaUi
        }
        error(
            "IosImageDecoder: could not decode the supplied ${bytes.size}-byte image " +
                "(Skia + ImageIO + UIImage all failed; container may be unsupported/corrupt)",
        )
    }

    /** ImageIO thumbnail at source long-edge = full-res, EXIF-baked HEIF. No JPEG transcode. */
    private fun decodeHeifViaImageIO(bytes: ByteArray): SkiaImage? = runCatching {
        val meta = IosImageIODecoder.metadataFromBytes(bytes)
        val edge = maxOf(meta.width, meta.height).coerceAtLeast(1)
        IosImageIODecoder.decodeThumbnailSkiaFromBytes(bytes, edge, shouldCache = false)
    }.getOrNull()

    /**
     * UIImage → orientation-baked JPEG → Skia. Last-resort fallback when Skia/ImageIO cannot decode.
     * Intermediate is JPEG (not PNG) to bound memory on 12MP camera frames.
     */
    private fun decodeViaUIImage(bytes: ByteArray): SkiaImage? {
        if (bytes.isEmpty()) return null
        val nsData = IosByteArrayInterop.toNSData(bytes)
        val uiImage = UIImage.imageWithData(nsData) ?: return null
        val baked = bakeOrientation(uiImage) ?: return null
        // 0.92: high enough for full-res export fidelity; far smaller than PNG intermediates.
        val jpegData = UIImageJPEGRepresentation(baked, 0.92) ?: return null
        val jpegBytes = IosByteArrayInterop.fromNSData(jpegData)
        return try {
            SkiaImage.makeFromEncoded(jpegBytes)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Draw [image] into a fresh context sized to [UIImage.size] so `imageOrientation` is applied
     * into pixels. Without this, HEIC from the camera can re-encode with a non-upright buffer
     * while UIKit would have rotated on display.
     */
    private fun bakeOrientation(image: UIImage): UIImage? {
        val (w, h) = image.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return null
        // scale=1: pixel-true bake (not screen-scale); product wants source resolution.
        UIGraphicsBeginImageContextWithOptions(image.size, false, 1.0)
        return try {
            image.drawInRect(CGRectMake(0.0, 0.0, w, h))
            UIGraphicsGetImageFromCurrentImageContext()
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    /** ISO BMFF brands used by HEIC/HEIF stills (and common variants). */
    private fun looksLikeHeif(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // box size (4) + 'ftyp' (4) + major brand (4)
        if (bytes[4] != 'f'.code.toByte() ||
            bytes[5] != 't'.code.toByte() ||
            bytes[6] != 'y'.code.toByte() ||
            bytes[7] != 'p'.code.toByte()
        ) {
            return false
        }
        val brand = byteArrayOf(bytes[8], bytes[9], bytes[10], bytes[11])
            .decodeToString()
            .lowercase()
        return brand == "heic" ||
            brand == "heif" ||
            brand == "mif1" ||
            brand == "msf1" ||
            brand == "heix" ||
            brand == "hevc" ||
            brand == "hevx"
    }
}
