package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Deterministic Desktop EXIF JPEG fixtures for tests (no binary assets).
 * Base image: bright TOP-LEFT quadrant on black; splice Orientation APP1 after SOI.
 */
object DesktopExifTestFixture {
    const val BaseWidth = 24
    const val BaseHeight = 16

    enum class Quad { TL, TR, BL, BR }

    fun jpegWithOrientation(orientation: Int): ByteArray {
        val base = baseJpeg()
        require(base[0].toInt() and 0xFF == 0xFF && base[1].toInt() and 0xFF == 0xD8) { "base is not a JPEG" }
        val tiff = byteArrayOf(
            0x4D, 0x4D,
            0x00, 0x2A,
            0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12,
            0x00, 0x03,
            0x00, 0x00, 0x00, 0x01,
            0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val exifHeader = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        )
        val payload = exifHeader + tiff
        val segLen = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            (segLen ushr 8).toByte(), (segLen and 0xFF).toByte(),
        ) + payload
        return byteArrayOf(base[0], base[1]) + app1 + base.copyOfRange(2, base.size)
    }

    fun jpegWithHugeIfdOffset(): ByteArray {
        val base = baseJpeg()
        require(base[0].toInt() and 0xFF == 0xFF && base[1].toInt() and 0xFF == 0xD8) { "base is not a JPEG" }
        val tiff = byteArrayOf(
            0x4D, 0x4D,
            0x00, 0x2A,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val exifHeader = byteArrayOf(
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
        )
        val payload = exifHeader + tiff
        val segLen = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            (segLen ushr 8).toByte(), (segLen and 0xFF).toByte(),
        ) + payload
        return byteArrayOf(base[0], base[1]) + app1 + base.copyOfRange(2, base.size)
    }

    fun brightestQuadrant(b: ImageBitmap): Quad {
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

    /** Plain JPEG of the base image with no EXIF APP1 segment. */
    fun plainJpegWithoutExif(): ByteArray = baseJpeg()

    /** Plain PNG of the base image (no EXIF). */
    fun plainPngWithoutExif(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(baseImage(), "png", out)
        return out.toByteArray()
    }

    /**
     * True when [bytes] contains a JPEG APP1 Exif segment (`FF E1` + `Exif\0\0`).
     * Used to prove final Spine JPEG encodes strip source orientation metadata.
     */
    fun containsExifApp1(bytes: ByteArray): Boolean {
        var i = 0
        while (i + 5 < bytes.size) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xE1.toByte()) {
                // APP1 length is big-endian at i+2; payload starts at i+4
                if (i + 9 < bytes.size &&
                    bytes[i + 4] == 'E'.code.toByte() &&
                    bytes[i + 5] == 'x'.code.toByte() &&
                    bytes[i + 6] == 'i'.code.toByte() &&
                    bytes[i + 7] == 'f'.code.toByte() &&
                    bytes[i + 8] == 0.toByte() &&
                    bytes[i + 9] == 0.toByte()
                ) {
                    return true
                }
            }
            i++
        }
        return false
    }

    private fun baseImage(): BufferedImage {
        val img = BufferedImage(BaseWidth, BaseHeight, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, BaseWidth, BaseHeight)
        g.color = Color.WHITE
        g.fillRect(0, 0, BaseWidth / 2, BaseHeight / 2)
        g.dispose()
        return img
    }

    private fun baseJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(baseImage(), "jpg", out)
        return out.toByteArray()
    }
}
