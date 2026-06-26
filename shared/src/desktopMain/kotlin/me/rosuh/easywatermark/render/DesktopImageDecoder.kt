package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * S4d-20A: the **Desktop platform image-decode boundary** — turns a real encoded image (bytes or a file)
 * into a Compose [ImageBitmap] via AWT `ImageIO`, so the accepted commonMain composition pipeline
 * ([WatermarkCellComposer.composeOverBackground]) can watermark an actually-decoded photo (not only a
 * generated in-memory background).
 *
 * This is the ADR-0004 decode boundary made concrete on Desktop: decode stays **platform-side**
 * (`ImageIO.read` → `BufferedImage.toComposeImageBitmap()`); commonMain keeps its clean contract of
 * *already-decoded `ImageBitmap` in, composed `ImageBitmap` out* — NO commonMain decode API is added. The
 * Android decode path is unchanged (it stays `BitmapFactory` in `:app`); the iOS decoder is a later slice
 * (`UIImage`/`ImageIO`), reusing the same commonMain composition.
 *
 * Uses only the JDK's bundled `javax.imageio` + the Compose-Desktop `toComposeImageBitmap()` bridge
 * (already on the desktop classpath via S4d-18's `compose.desktop.currentOs`) — **no new dependency**.
 *
 * ## S4d-21: EXIF orientation baked in at the decode edge
 * `ImageIO.read` returns the JPEG's stored pixels WITHOUT applying EXIF orientation, so a camera photo
 * tagged "rotate 90°" would decode sideways. Android's product decode path bakes EXIF rotation into the
 * bitmap (`BitmapUtils`); this boundary now matches that policy on Desktop: it parses the JPEG EXIF
 * Orientation tag (a tiny local APP1/TIFF reader — **no metadata dependency**) and applies the matching
 * rotation/flip to the decoded `BufferedImage` BEFORE bridging to Compose, so callers
 * ([DesktopWatermarkComposer.composeOverRealImage]) always receive an **upright** `ImageBitmap` whose
 * dimensions reflect orientation (6/8 swap width/height). Orientation parsing is best-effort: any
 * non-JPEG / missing / malformed EXIF yields orientation 1 (no transform). commonMain stays decode-free —
 * orientation is purely a platform decode-edge concern.
 */
object DesktopImageDecoder {

    /**
     * Decode encoded image [bytes] (PNG/JPEG/… anything the JVM's `ImageIO` supports) into an
     * [ImageBitmap], applying EXIF orientation (JPEG) so the result is upright. Throws
     * [IllegalStateException] if `ImageIO` cannot decode (unsupported/corrupt) — `ImageIO.read` returns
     * `null` rather than throwing for an unrecognised format.
     */
    fun decode(bytes: ByteArray): ImageBitmap {
        val buffered = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
            ?: error("DesktopImageDecoder: ImageIO could not decode the supplied ${bytes.size}-byte image (unsupported/corrupt)")
        val oriented = applyExifOrientation(buffered, parseExifOrientation(bytes))
        return oriented.toComposeImageBitmap()
    }

    /** Decode an image [file] from disk into an upright [ImageBitmap] (EXIF orientation applied). */
    fun decode(file: File): ImageBitmap {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) error("DesktopImageDecoder: file ${file.path} is empty/missing")
        return decode(bytes)
    }

    // ---- EXIF orientation (tiny local JPEG APP1/TIFF reader — no dependency) ---------------------

    /**
     * Parse the JPEG EXIF Orientation tag (0x0112) from raw [bytes]. Returns 1..8 (1 = normal) or **1** for
     * any non-JPEG / missing-EXIF / malformed input (best-effort, never throws). Scans the JPEG marker
     * segments for APP1 (`0xFFE1`) carrying the `Exif\0\0` header, then reads the IFD0 entry for tag
     * 0x0112 from the embedded TIFF block (honouring `II`/`MM` byte order).
     */
    internal fun parseExifOrientation(bytes: ByteArray): Int {
        // JPEG SOI = FF D8.
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return 1
        var p = 2
        while (p + 4 <= bytes.size) {
            if ((bytes[p].toInt() and 0xFF) != 0xFF) return 1 // not at a marker → give up
            val marker = bytes[p + 1].toInt() and 0xFF
            // Standalone markers (no length): RSTn / SOI / EOI / TEM — none carry EXIF; stop at SOS (DA).
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker in 0xD0..0xD7)) { p += 2; continue }
            if (marker == 0xDA) return 1 // start of scan — header is over, no EXIF found
            val segLen = ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
            if (segLen < 2 || p + 2 + segLen > bytes.size) return 1
            val payloadStart = p + 4
            val payloadLen = segLen - 2
            if (marker == 0xE1 && payloadLen >= 6 &&
                bytes[payloadStart] == 'E'.code.toByte() && bytes[payloadStart + 1] == 'x'.code.toByte() &&
                bytes[payloadStart + 2] == 'i'.code.toByte() && bytes[payloadStart + 3] == 'f'.code.toByte() &&
                bytes[payloadStart + 4].toInt() == 0 && bytes[payloadStart + 5].toInt() == 0
            ) {
                return readOrientationFromTiff(bytes, payloadStart + 6, payloadStart + payloadLen)
            }
            p += 2 + segLen
        }
        return 1
    }

    /** Read tag 0x0112 from the TIFF block [tiffStart, end). Returns 1 on any malformation. */
    private fun readOrientationFromTiff(bytes: ByteArray, tiffStart: Int, end: Int): Int {
        val tiffStartLong = tiffStart.toLong()
        val endLong = end.toLong()
        fun hasTiffRange(off: Long, byteCount: Long): Boolean =
            byteCount >= 0 && tiffStartLong <= off && off <= endLong - byteCount

        if (tiffStart < 0 || end > bytes.size || tiffStartLong > endLong || !hasTiffRange(tiffStartLong, 8)) return 1
        val little = when {
            bytes[tiffStart].toInt() and 0xFF == 0x49 && bytes[tiffStart + 1].toInt() and 0xFF == 0x49 -> true  // "II"
            bytes[tiffStart].toInt() and 0xFF == 0x4D && bytes[tiffStart + 1].toInt() and 0xFF == 0x4D -> false // "MM"
            else -> return 1
        }
        fun u16(off: Int): Int = if (!hasTiffRange(off.toLong(), 2)) -1 else if (little) {
            (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
        }
        fun u32(off: Int): Long = if (!hasTiffRange(off.toLong(), 4)) -1 else if (little) {
            ((bytes[off].toInt() and 0xFF).toLong()) or ((bytes[off + 1].toInt() and 0xFF).toLong() shl 8) or
                ((bytes[off + 2].toInt() and 0xFF).toLong() shl 16) or ((bytes[off + 3].toInt() and 0xFF).toLong() shl 24)
        } else {
            ((bytes[off].toInt() and 0xFF).toLong() shl 24) or ((bytes[off + 1].toInt() and 0xFF).toLong() shl 16) or
                ((bytes[off + 2].toInt() and 0xFF).toLong() shl 8) or (bytes[off + 3].toInt() and 0xFF).toLong()
        }
        if (u16(tiffStart + 2) != 0x002A) return 1 // TIFF magic
        val ifdOffset = u32(tiffStart + 4)
        if (ifdOffset < 8 || ifdOffset == -1L) return 1
        val ifd0Long = tiffStartLong + ifdOffset
        if (!hasTiffRange(ifd0Long, 2)) return 1
        val ifd0 = ifd0Long.toInt()
        val count = u16(ifd0)
        if (count < 0) return 1
        val entriesStart = ifd0Long + 2
        val entriesByteCount = count.toLong() * 12
        if (!hasTiffRange(entriesStart, entriesByteCount)) return 1
        for (i in 0 until count) {
            val entry = (entriesStart + i.toLong() * 12).toInt()
            if (u16(entry) == 0x0112) { // Orientation, SHORT — value left-justified in the 4-byte value field
                val v = u16(entry + 8)
                return if (v in 1..8) v else 1
            }
        }
        return 1
    }

    /**
     * Apply EXIF [orientation] (1..8) to [src], returning an upright [BufferedImage]. Orientations 5–8 swap
     * width/height. Orientation 1 (or anything out of 1..8) returns [src] unchanged. Uses a 90°-multiple
     * (+ optional mirror) [AffineTransform] with NEAREST sampling — lossless for the orientation operations.
     *
     * Coordinate mappings (src `(x,y)` → dst), the standard EXIF orientation semantics:
     *  2 mirror-H `(W-1-x, y)`; 3 rotate-180 `(W-1-x, H-1-y)`; 4 mirror-V `(x, H-1-y)`;
     *  5 transpose `(y, x)`; 6 rotate-90-CW `(H-1-y, x)`; 7 transverse `(H-1-y, W-1-x)`;
     *  8 rotate-270-CW `(y, W-1-x)`.
     */
    internal fun applyExifOrientation(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return src
        val w = src.width
        val h = src.height
        val swap = orientation in 5..8
        val dw = if (swap) h else w
        val dh = if (swap) w else h
        // AffineTransform maps SOURCE → DEST: (dstX,dstY) = (m00*x + m01*y + m02, m10*x + m11*y + m12).
        val t: AffineTransform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w.toDouble(), 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w.toDouble(), h.toDouble())
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h.toDouble())
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h.toDouble(), 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h.toDouble(), w.toDouble())
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w.toDouble())
            else -> return src
        }
        val dst = BufferedImage(dw, dh, BufferedImage.TYPE_INT_ARGB)
        AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(src, dst)
        return dst
    }
}
