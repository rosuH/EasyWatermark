package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

/**
 * The **iOS platform image-decode boundary** — the iOS analogue of the Desktop * [DesktopImageDecoder]. Decodes a real encoded image (PNG/JPEG/… bytes) into a Compose
 * [ImageBitmap] so the accepted commonMain composition pipeline
 * ([WatermarkCellComposer.composeOverBackground]) can watermark an actually-decoded photo.
 *
 * iOS has no `javax.imageio`, so decode goes through **Skia** (`org.jetbrains.skia.Image.makeFromEncoded`),
 * which ships with the Compose-Multiplatform iOS artifacts (skiko) — **no new dependency**. The Skia
 * `Image` is bridged to Compose with `toComposeImageBitmap()` (the same skiko bridge the desktop side
 * uses for `BufferedImage`).
 *
 * This keeps the ADR-0004 decode boundary platform-side: decode lives in `iosMain`; commonMain stays
 * **decode-free** (already-decoded `ImageBitmap` in, composed out). A production iOS app would obtain the
 * bytes from PHPicker/`UIImage`/file (C5); this boundary only needs the encoded bytes.
 *
 * ## : EXIF orientation is already honoured by the Skia decode — no extra transform needed
 * Unlike Android (`BitmapFactory`) and Desktop (`ImageIO`), which return the JPEG's STORED pixels and
 * therefore need EXIF orientation baked in manually (Android `BitmapUtils`; Desktop `DesktopImageDecoder`
 * /22), **Skia's `Image.makeFromEncoded` → `toComposeImageBitmap()` already applies the EXIF
 * Orientation tag**: an orientation-6 (90° CW) JPEG decodes to an UPRIGHT bitmap with swapped dimensions.
 * This was proven on the SAME skiko/Skia behind the SAME `org.jetbrains.skia` API by the desktop proxy
 * gate `SkiaExifDecodeProbeTest` (desktop run, no iOS runtime needed). So this boundary deliberately does
 * **NOT** apply any further rotation — doing so would DOUBLE-rotate camera photos. The iOS gate
 * `IosExifOrientationTest` asserts decode(orientation-6) is upright; its RUN confirms the iOS-runtime
 * behaviour at /C5 (compile/link-proven here). commonMain stays decode-free.
 */
object IosImageDecoder {

    /**
 * Decode encoded image [bytes] into an [ImageBitmap] via Skia. Skia applies EXIF orientation during
 * Decode (see the object KDoc), so the result is already upright — no manual orientation transform is * applied. Throws [IllegalStateException] if Skia cannot decode (unsupported/corrupt) so callers fail
 * loudly instead of propagating a bad image.
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
        return scaleSkia(decodeSkia(bytes), maxEdgePx).toComposeImageBitmap()
    }

    /**
 * Re-encode [bytes] as PNG with longest edge ≤ [maxEdgePx] for **on-screen preview export**.
 * Full-res camera photos (12MP+) make Skiko watermark raster multi-second; preview does not
 * Need full resolution.     */
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
        return try {
            SkiaImage.makeFromEncoded(bytes)
        } catch (t: Throwable) {
            error(
                "IosImageDecoder: Skia could not decode the supplied ${bytes.size}-byte image " +
                    "(unsupported/corrupt): ${t.message}",
            )
        }
    }
}
