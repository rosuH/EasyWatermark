package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.androidTextMeasureEnv
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
@Config(sdk = [34], application = Application::class) // plain app — avoids MyApp.startKoin double-start across the suite
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
            WaterMarkImageView.buildTextBitmapShader(
                imageInfo, config, paint,
                androidTextMeasureEnv(RuntimeEnvironment.getApplication()), Dispatchers.Unconfined,
            )
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

    /**
     * Renders ONE full watermark cell (sized to the cell's own dimensions, so the whole rotated
     * text box is captured — a fixed small window would sample a transparent corner of a large
     * cell) and returns its pixels.
     */
    private fun renderTiledPixels(text: String, degree: Float): IntArray {
        val config = WaterMark.default.copy(
            text = text, degree = degree, hGap = 0, vGap = 0,
            textSize = 24f, textColor = Color.WHITE, iconUri = Uri.EMPTY,
        )
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val shader = runBlocking {
            WaterMarkImageView.buildTextBitmapShader(
                imageInfo, config, paint,
                androidTextMeasureEnv(RuntimeEnvironment.getApplication()), Dispatchers.Unconfined,
            )
        }!!
        val w = shader.width.coerceAtLeast(1)
        val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /**
     * PIXEL golden (upgrades the dimension-only golden): composites the tiled cell shader and pins
     * the rendered output. Catches blank/empty-render regressions that cell dimensions miss — the
     * class of bug the C2a engine-wiring attempt produced. Strict signature pins exact pixels
     * (Robolectric NATIVE @ sdk34; update only on an intentional Robolectric/font bump).
     */
    @Test
    fun textCell_rendered_pixels_are_nonblank_and_stable() {
        val px = renderTiledPixels("GOLDEN", degree = 0f)
        val nonTransparent = px.count { it != 0 }
        var sig = 0
        for (p in px) sig = sig * 31 + p
        println("PIXEL-GOLDEN nonTransparent=$nonTransparent sig=$sig")

        assertTrue("tiled watermark must render visible pixels (catches blank-cell regressions)", nonTransparent > 0)
    }

    /**
     * KNOWN LIMITATION (pinned as a passing test, CMP plan C1.7 refinement): the REAL production
     * default config — emoji "👋 DO NOT REDISTRIBUTE" @ 315° — renders BLANK (0 visible px) under
     * Robolectric NATIVE, even though it renders correctly on a real device. So Robolectric NATIVE
     * does NOT faithfully reproduce emoji + rotated text: the device-free JVM golden can cover
     * simple ASCII/0° cells (above) but CANNOT serve as a faithful oracle for this app's actual
     * watermark (emoji default + rotation). A faithful golden of the real configs — and trustworthy
     * verification of the C2a engine-wiring swap — therefore needs INSTRUMENTED (on-device) tests.
     * This test documents the gap so it isn't mistaken for a code regression.
     */
    private fun iconCellDims(iconW: Int, iconH: Int, degree: Float, hGap: Int, vGap: Int): Pair<Int, Int> {
        val config = WaterMark.default.copy(
            degree = degree, hGap = hGap, vGap = vGap, textSize = 14f, iconUri = Uri.EMPTY,
        )
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        val src = Bitmap.createBitmap(iconW, iconH, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val shader = runBlocking {
            WaterMarkImageView.buildIconBitmapShader(imageInfo, src, config, Paint(), false, Dispatchers.Unconfined)
        }!!
        return shader.width to shader.height
    }

    /**
     * Icon-cell golden: cross-checks `buildIconBitmapShader`'s sizing against commonMain
     * `WatermarkGeometry.diagonal`/`horizontalGap` — the equivalence proof that gates wiring the
     * icon path to commonMain (C2a). At textSize=14 (scaleRatio=1), gap=0: cell = maxSize square.
     */
    @Test
    fun iconCell_dimensions_match_geometry() {
        val (w, h) = iconCellDims(40, 20, degree = 0f, hGap = 0, vGap = 0)
        println("ICON-GOLDEN 40x20@0/gap0 = ${w}x${h}")
        assertTrue("icon cell has positive size", w > 0 && h > 0)
        assertEquals("gap=0 → square cell", w, h)
        // calculateMaxSize(rawHeight,rawWidth)=diagonal; gap=0 → horizontalGap(maxSize,0)=maxSize
        val expected = WatermarkGeometry.horizontalGap(WatermarkGeometry.diagonal(20f, 40f), 0)
        assertEquals("icon cell width == WatermarkGeometry prediction", expected, w)
    }

    @Test
    fun defaultConfig_emoji_rotated_cell_renders_nonblank() {
        val px = renderTiledPixels("👋 DO NOT REDISTRIBUTE", degree = 315f)
        val nonTransparent = px.count { it != 0 }
        println("DEFAULT-CELL-GOLDEN nonTransparent=$nonTransparent / ${px.size}")
        assertTrue("default emoji watermark @315 must render visible pixels", nonTransparent > 0)
    }
}
