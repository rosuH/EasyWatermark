package me.rosuh.easywatermark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * INSTRUMENTED golden (CMP plan C1.7) — runs on a real device, so it faithfully renders the real
 * watermark configs (emoji + rotation) that Robolectric NATIVE renders blank (see the JVM
 * WatermarkCellGoldenTest "known gap"). This is the trustworthy oracle the C2a engine-wiring swap
 * must be verified against before delegating the live renderer to commonMain WatermarkGeometry.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkCellInstrumentedGoldenTest {

    // Sizes the output to the cell's own dimensions so the full rotated text box is captured
    // (a fixed small window samples a transparent corner of a large emoji/rotated cell).
    private fun renderTiledPixels(text: String, degree: Float): IntArray {
        val config = WaterMark.default.copy(
            text = text, degree = degree, hGap = 0, vGap = 0,
            textSize = 24f, textColor = Color.WHITE, iconUri = Uri.EMPTY,
        )
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val shader = runBlocking {
            WatermarkRenderer.buildTextShader(
                imageInfo, config, paint,
                androidTextMeasureEnv(InstrumentationRegistry.getInstrumentation().targetContext),
                Dispatchers.Unconfined,
            )
        }!!
        val w = shader.width.coerceAtLeast(1)
        val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    @Test
    fun realDefaultConfig_emoji_rotated_renders_nonblank_on_device() {
        val px = renderTiledPixels("👋 DO NOT REDISTRIBUTE", degree = 315f)
        val nonTransparent = px.count { it != 0 }
        Log.i("INSTR-GOLDEN", "emoji@315 nonTransparent=$nonTransparent / ${px.size}")
        assertTrue("real emoji watermark @315 must render visible pixels on device", nonTransparent > 0)
    }

    @Test
    fun asciiCell_renders_nonblank_on_device() {
        val px = renderTiledPixels("GOLDEN", degree = 0f)
        val nonTransparent = px.count { it != 0 }
        Log.i("INSTR-GOLDEN", "ascii@0 nonTransparent=$nonTransparent / ${px.size}")
        assertTrue("ascii watermark renders visible pixels on device", nonTransparent > 0)
    }
}
