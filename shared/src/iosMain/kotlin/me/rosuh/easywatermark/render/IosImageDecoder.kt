package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * The **iOS platform image-decode boundary** — the iOS analogue of Desktop [DesktopImageDecoder].
 * Decodes a real encoded image (PNG/JPEG/… bytes) into a Compose [ImageBitmap] for product paint.
 * C3 Final Export ([IosFinalRenderSpine]) and Preview ([IosPreviewRaster]) call full-res
 * [decode] / [decodeThumbnail] here, then compose via [CommonWatermarkPipeline]; never re-rotate
 * after decode (orientation is baked once at this edge).
 *
 * ## Decode strategy
 * - **JPEG/PNG (and other Skia-supported formats):** Skia `Image.makeFromEncoded` — still the
 *   primary path; Skia applies EXIF orientation during decode (see [IosExifOrientationTest]).
 * - **HEIF/HEIC (Photos `.current` originals):** Apple ImageIO via [IosImageIODecoder], because
 *   Skia cannot decode the HEIF payloads staged from PHPicker. Pixels are bridged directly into
 *   an owned premultiplied sRGB Skia raster — **no** JPEG/PNG intermediate, no Coil.
 * - **Unknown/unsupported by Skia:** one ImageIO attempt as a general fallback before failing with
 *   the same loud [IllegalStateException] contract callers already rely on (filmstrip catches it).
 *   Only [Exception] is caught for fallback; [Error] (including OOM) propagates without a second
 *   decoder attempt.
 *
 * Thumbnail requests for HEIF use ImageIO's `kCGImageSourceThumbnailMaxPixelSize` so the native
 * decode is bounded; a non-positive bound fails closed; thumbnail failure never falls back to
 * full-resolution decode. JPEG/PNG still decode then scale in Skia (unchanged behavior).
 *
 * This keeps the ADR-0004 decode boundary platform-side: decode lives in `iosMain`; commonMain stays
 * **decode-free** (already-decoded `ImageBitmap` in, composed out).
 */
/** J5: decode edge — not called from Swift (goes through bridges). */
internal object IosImageDecoder {

    /**
     * Decode encoded image [bytes] into an [ImageBitmap]. Orientation is baked once at this edge
     * (Skia EXIF for JPEG/PNG; ImageIO transform for HEIF). Throws [IllegalStateException] if neither
     * Skia nor ImageIO can decode so callers fail loudly instead of propagating a bad image.
     */
    fun decode(bytes: ByteArray): ImageBitmap {
        return decodeToSkia(bytes, maxEdgePx = null).toComposeImageBitmap()
    }

    /**
     * Decode and downscale so the longer edge is at most [maxEdgePx]. Used for filmstrip cells
     * (≈40dp) so multi-pick does not decode multi-megapixel bitmaps for every thumbnail.
     * [maxEdgePx] must be positive. HEIF/HEIC bounds the native ImageIO request and fails closed
     * on thumbnail miss; other formats scale after Skia decode.
     */
    fun decodeThumbnail(bytes: ByteArray, maxEdgePx: Int = 160): ImageBitmap {
        if (maxEdgePx <= 0) {
            error("IosImageDecoder: decodeThumbnail maxEdgePx must be positive, was $maxEdgePx")
        }
        return decodeToSkia(bytes, maxEdgePx = maxEdgePx).toComposeImageBitmap()
    }

    /**
     * Re-encode [bytes] as PNG with longest edge ≤ [maxEdgePx] for **on-screen preview export**.
     * Full-res camera photos (12MP+) make Skiko watermark raster multi-second; preview does not
     * need full resolution. [maxEdgePx] must be positive.
     */
    fun downscaleEncodedToPng(bytes: ByteArray, maxEdgePx: Int = 1600): ByteArray {
        if (maxEdgePx <= 0) {
            error("IosImageDecoder: downscaleEncodedToPng maxEdgePx must be positive, was $maxEdgePx")
        }
        val scaled = decodeToSkia(bytes, maxEdgePx = maxEdgePx)
        val data = scaled.encodeToData(EncodedImageFormat.PNG)
            ?: error("IosImageDecoder: failed to encode downscaled PNG")
        return data.bytes
    }

    private fun decodeToSkia(bytes: ByteArray, maxEdgePx: Int?): SkiaImage {
        if (bytes.isEmpty()) {
            error("IosImageDecoder: empty image bytes")
        }
        // Known HEIF/HEIC → ImageIO first (Skia cannot decode these payloads).
        if (IosImageIODecoder.looksLikeHeif(bytes)) {
            return try {
                IosImageIODecoder.decodeToSkia(bytes, maxEdgePx)
            } catch (e: Exception) {
                // Preserve Error/OOM propagation; only wrap ordinary decoder exceptions.
                error(
                    "IosImageDecoder: ImageIO could not decode HEIF/HEIC (${bytes.size} bytes): " +
                        (e.message ?: e.toString()),
                )
            }
        }
        // Skia primary path for JPEG/PNG/…
        return try {
            val skia = SkiaImage.makeFromEncoded(bytes)
            if (maxEdgePx != null) scaleSkia(skia, maxEdgePx) else skia
        } catch (skiaFailure: Exception) {
            // General ImageIO fallback for formats Skia rejects but Apple can still open.
            // Do not catch Error — OOM must not launch a second full decoder.
            try {
                IosImageIODecoder.decodeToSkia(bytes, maxEdgePx)
            } catch (imageIoFailure: Exception) {
                error(
                    "IosImageDecoder: Skia could not decode the supplied ${bytes.size}-byte image " +
                        "(unsupported/corrupt): ${skiaFailure.message}; ImageIO fallback also failed: " +
                        (imageIoFailure.message ?: imageIoFailure.toString()),
                )
            }
        }
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
}
