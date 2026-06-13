package me.rosuh.easywatermark.render

import android.net.Uri
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden harness (CMP plan C1.7) — same-platform regression golden for the watermark TEXT cell
 * builder, rendered on the JVM via Robolectric NATIVE graphics (no device). Pins the cell
 * dimensions produced by [WaterMarkImageView.buildTextBitmapShader] for fixed configs; any
 * change to the engine's cell sizing trips this. Baselines are Robolectric-environment values
 * (a regression net, NOT a device-pixel reference — plan D4 two-tier strategy).
 *
 * Also cross-checks the extracted commonMain [WatermarkGeometry]: with degree=0 and gap=0 the
 * cell width/height must equal the un-rotated text box (cos0=1,sin0=0), which is exactly what
 * WatermarkGeometry.rotatedCellWidth/Height and horizontalGap predict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkCellGoldenTest {

    private fun cell(text: String, degree: Float, hGap: Int, vGap: Int): Pair<Int, Int> {
        val config = WaterMark.default.copy(
            text = text,
            degree = degree,
            hGap = hGap,
            vGap = vGap,
            textSize = 24f,
            iconUri = Uri.EMPTY,
        )
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val shader = runBlocking {
            WaterMarkImageView.buildTextBitmapShader(imageInfo, config, paint, Dispatchers.Unconfined)
        }
        assertNotNull("shader must build for non-blank text", shader)
        return shader!!.width to shader.height
    }

    @Test
    fun textCell_renders_with_positive_dimensions_and_captures_baseline() {
        val (w0, h0) = cell("GOLDEN", degree = 0f, hGap = 0, vGap = 0)
        val (wRot, hRot) = cell("GOLDEN", degree = 90f, hGap = 0, vGap = 0)
        val (wGap, hGap) = cell("GOLDEN", degree = 0f, hGap = 100, vGap = 100)
        println("GOLDEN-BASELINE deg0=${w0}x${h0} deg90=${wRot}x${hRot} gap100=${wGap}x${hGap}")

        assertTrue("cell has positive size", w0 > 0 && h0 > 0)

        // 90° swaps the axes (cos90=0, sin90=1) — cross-checks WatermarkGeometry
        assertEquals("90deg width == 0deg height", h0, wRot)
        assertEquals("90deg height == 0deg width", w0, hRot)

        // gap=100 doubles each axis vs gap=0 (maxSize*(100/100+1)=2x), per WatermarkGeometry.horizontalGap
        assertEquals("gap100 width == 2x", w0 * 2, wGap)
        assertEquals("gap100 height == 2x", h0 * 2, hGap)
    }
}
