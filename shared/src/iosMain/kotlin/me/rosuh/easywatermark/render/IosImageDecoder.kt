package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

/**
 * S4d-20B: the **iOS platform image-decode boundary** — the iOS analogue of the Desktop
 * [DesktopImageDecoder] (S4d-20A). Decodes a real encoded image (PNG/JPEG/… bytes) into a Compose
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
 * ## S4d-23: EXIF orientation is already honoured by the Skia decode — no extra transform needed
 * Unlike Android (`BitmapFactory`) and Desktop (`ImageIO`), which return the JPEG's STORED pixels and
 * therefore need EXIF orientation baked in manually (Android `BitmapUtils`; Desktop `DesktopImageDecoder`
 * S4d-21/22), **Skia's `Image.makeFromEncoded` → `toComposeImageBitmap()` already applies the EXIF
 * Orientation tag**: an orientation-6 (90° CW) JPEG decodes to an UPRIGHT bitmap with swapped dimensions.
 * This was proven on the SAME skiko/Skia behind the SAME `org.jetbrains.skia` API by the desktop proxy
 * gate `SkiaExifDecodeProbeTest` (desktop run, no iOS runtime needed). So this boundary deliberately does
 * **NOT** apply any further rotation — doing so would DOUBLE-rotate camera photos. The iOS gate
 * `IosExifOrientationTest` asserts decode(orientation-6) is upright; its RUN confirms the iOS-runtime
 * behaviour at S4d-20C/C5 (compile/link-proven here). commonMain stays decode-free.
 */
object IosImageDecoder {

    /**
     * Decode encoded image [bytes] into an [ImageBitmap] via Skia. Skia applies EXIF orientation during
     * decode (see the object KDoc), so the result is already upright — no manual orientation transform is
     * applied. Throws [IllegalStateException] if Skia cannot decode (unsupported/corrupt) so callers fail
     * loudly instead of propagating a bad image.
     */
    fun decode(bytes: ByteArray): ImageBitmap {
        val skiaImage = try {
            SkiaImage.makeFromEncoded(bytes)
        } catch (t: Throwable) {
            error("IosImageDecoder: Skia could not decode the supplied ${bytes.size}-byte image (unsupported/corrupt): ${t.message}")
        }
        return skiaImage.toComposeImageBitmap()
    }
}
