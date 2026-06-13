package me.rosuh.easywatermark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Validates Robolectric NATIVE graphics actually rasterizes pixels on the JVM — the prerequisite
 * for a device-free watermark golden-image harness (CMP plan C1.7). With LEGACY (no-op shadow)
 * graphics, drawn pixels are not real; NATIVE mode rasterizes via the bundled Skia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobolectricGraphicsSmokeTest {

    @Test
    fun nativeGraphics_rasterizes_real_pixels() {
        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.RED)
        assertEquals(Color.RED, bmp.getPixel(5, 5))
    }
}
