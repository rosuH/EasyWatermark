package me.rosuh.easywatermark.utils.bitmap

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

internal object AndroidExifTestFixture {

    const val BaseWidth = 48
    const val BaseHeight = 32

    enum class Quadrant { TopLeft, TopRight, BottomLeft, BottomRight }

    fun jpegWithOrientation(
        orientation: Int,
        width: Int = BaseWidth,
        height: Int = BaseHeight,
    ): ByteArray {
        val base = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
                for (y in 0 until height / 2) {
                    for (x in 0 until width / 2) {
                        setPixel(x, y, Color.WHITE)
                    }
                }
            }.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
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
        val payload = byteArrayOf(
            'E'.code.toByte(),
            'x'.code.toByte(),
            'i'.code.toByte(),
            'f'.code.toByte(),
            0,
            0,
        ) + tiff
        val segmentLength = payload.size + 2
        val app1 = byteArrayOf(
            0xFF.toByte(),
            0xE1.toByte(),
            (segmentLength ushr 8).toByte(),
            segmentLength.toByte(),
        ) + payload
        return byteArrayOf(base[0], base[1]) + app1 + base.copyOfRange(2, base.size)
    }

    fun brightestQuadrant(bitmap: Bitmap): Quadrant {
        val sums = LongArray(4)
        val counts = IntArray(4)
        val middleX = bitmap.width / 2
        val middleY = bitmap.height / 2
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val quadrant = (if (y < middleY) 0 else 2) + (if (x < middleX) 0 else 1)
                sums[quadrant] += Color.red(color) + Color.green(color) + Color.blue(color)
                counts[quadrant] += 1
            }
        }
        val brightest = sums.indices.maxBy { sums[it].toDouble() / counts[it] }
        return when (brightest) {
            0 -> Quadrant.TopLeft
            1 -> Quadrant.TopRight
            2 -> Quadrant.BottomLeft
            else -> Quadrant.BottomRight
        }
    }
}
