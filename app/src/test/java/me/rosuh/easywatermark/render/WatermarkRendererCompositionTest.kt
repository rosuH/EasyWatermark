package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import androidx.core.graphics.withSave
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.ui.widget.utils.WaterMarkShader
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2a extraction proof for the composition helper. Pins that the unified
 * [WatermarkRenderer.compose] reproduces — pixel-for-pixel — the two former, separate composition
 * branches it replaced:
 *
 *  - the EXPORT branch in `MainViewModel.generateImage` (no `withSave`, no translate for REPEAT,
 *    composited at canvas origin), and
 *  - the PREVIEW branch in `WaterMarkImageView.onDraw` (`withSave`, translate by drawable bounds).
 *
 * The cell-builder extraction (`buildTextShader`/`buildIconShader`) is guarded separately by the S0
 * strict export golden via the retained `WaterMarkImageView.build*BitmapShader` wrappers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkRendererCompositionTest {

    private fun buildShader(tile: WatermarkTileMode): WaterMarkShader {
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        val config = WaterMark.default.copy(
            text = "GOLDEN", degree = 0f, hGap = 0, vGap = 0,
            textSize = 24f, textColor = Color.WHITE, iconUri = Uri.EMPTY, tileMode = tile,
        )
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        return runBlocking {
            WatermarkRenderer.buildTextShader(
                imageInfo, config, paint,
                androidTextMeasureEnv(RuntimeEnvironment.getApplication()), Dispatchers.Unconfined,
            )
        }!!
    }

    private fun newBitmap(bg: Int, w: Int = 200, h: Int = 200): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(bg) }

    private fun pixels(b: Bitmap): IntArray =
        IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Verbatim copy of the PRE-S2a export composition (MainViewModel.generateImage ~lines 372-394). */
    private fun oldExportCompose(
        canvas: Canvas, shader: WaterMarkShader?, tile: Shader.TileMode, paint: Paint,
        bmpW: Int, bmpH: Int, offsetX: Float, offsetY: Float,
    ) {
        paint.shader = shader?.bitmapShader
        if (tile == Shader.TileMode.CLAMP) {
            canvas.translate(0 + offsetX * bmpW, 0 + offsetY * bmpH)
            canvas.drawRect(0f, 0f, (shader?.width ?: 0).toFloat(), (shader?.height ?: 0).toFloat(), paint)
        } else {
            canvas.drawRect(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), paint)
        }
    }

    /** Verbatim copy of the PRE-S2a preview composition (WaterMarkImageView.onDraw ~lines 300-324). */
    private fun oldPreviewCompose(
        canvas: Canvas, shader: WaterMarkShader?, tile: Shader.TileMode, paint: Paint,
        left: Float, top: Float, regionW: Float, regionH: Float, offsetX: Float, offsetY: Float,
    ) {
        paint.shader = shader?.bitmapShader
        canvas.withSave {
            if (tile == Shader.TileMode.CLAMP) {
                translate(left + offsetX * regionW, top + offsetY * regionH)
                drawRect(0f, 0f, (shader?.width ?: 0).toFloat(), (shader?.height ?: 0).toFloat(), paint)
            } else {
                translate(left, top)
                drawRect(0f, 0f, regionW, regionH, paint)
            }
        }
    }

    // ---- export-shape equivalence (left/top = 0, region = full bitmap) -------------------------

    @Test
    fun compose_matches_legacy_export_repeat() {
        val shader = buildShader(WatermarkTileMode.REPEAT)
        val a = newBitmap(Color.DKGRAY)
        WatermarkRenderer.compose(Canvas(a), shader, Shader.TileMode.REPEAT, Paint(), 0f, 0f, 200f, 200f, 0.3f, 0.4f)
        val b = newBitmap(Color.DKGRAY)
        oldExportCompose(Canvas(b), shader, Shader.TileMode.REPEAT, Paint(), 200, 200, 0.3f, 0.4f)
        assertArrayEquals(pixels(b), pixels(a))
    }

    @Test
    fun compose_matches_legacy_export_clamp() {
        val shader = buildShader(WatermarkTileMode.CLAMP)
        val a = newBitmap(Color.DKGRAY)
        WatermarkRenderer.compose(Canvas(a), shader, Shader.TileMode.CLAMP, Paint(), 0f, 0f, 200f, 200f, 0.3f, 0.4f)
        val b = newBitmap(Color.DKGRAY)
        oldExportCompose(Canvas(b), shader, Shader.TileMode.CLAMP, Paint(), 200, 200, 0.3f, 0.4f)
        assertArrayEquals(pixels(b), pixels(a))
    }

    // ---- preview-shape equivalence (non-zero left/top, region = drawable bounds) ---------------

    @Test
    fun compose_matches_legacy_preview_repeat() {
        val shader = buildShader(WatermarkTileMode.REPEAT)
        val a = newBitmap(Color.DKGRAY)
        WatermarkRenderer.compose(Canvas(a), shader, Shader.TileMode.REPEAT, Paint(), 10f, 20f, 180f, 160f, 0.3f, 0.4f)
        val b = newBitmap(Color.DKGRAY)
        oldPreviewCompose(Canvas(b), shader, Shader.TileMode.REPEAT, Paint(), 10f, 20f, 180f, 160f, 0.3f, 0.4f)
        assertArrayEquals(pixels(b), pixels(a))
    }

    @Test
    fun compose_matches_legacy_preview_clamp() {
        val shader = buildShader(WatermarkTileMode.CLAMP)
        val a = newBitmap(Color.DKGRAY)
        WatermarkRenderer.compose(Canvas(a), shader, Shader.TileMode.CLAMP, Paint(), 10f, 20f, 180f, 160f, 0.3f, 0.4f)
        val b = newBitmap(Color.DKGRAY)
        oldPreviewCompose(Canvas(b), shader, Shader.TileMode.CLAMP, Paint(), 10f, 20f, 180f, 160f, 0.3f, 0.4f)
        assertArrayEquals(pixels(b), pixels(a))
    }

    // ---- null-shader edge quirks preserved -----------------------------------------------------

    @Test
    fun compose_matches_legacy_export_null_shader_repeat_fills_with_paint() {
        // Quirk: REPEAT with a null shader fills the whole region with the (default black) paint.
        val a = newBitmap(Color.DKGRAY)
        WatermarkRenderer.compose(Canvas(a), null, Shader.TileMode.REPEAT, Paint(), 0f, 0f, 200f, 200f, 0.3f, 0.4f)
        val b = newBitmap(Color.DKGRAY)
        oldExportCompose(Canvas(b), null, Shader.TileMode.REPEAT, Paint(), 200, 200, 0.3f, 0.4f)
        assertArrayEquals(pixels(b), pixels(a))
    }

    @Test
    fun compose_matches_legacy_export_null_shader_clamp_draws_nothing() {
        // Quirk: CLAMP with a null shader draws a 0x0 rect -> background untouched.
        val a = newBitmap(Color.DKGRAY)
        WatermarkRenderer.compose(Canvas(a), null, Shader.TileMode.CLAMP, Paint(), 0f, 0f, 200f, 200f, 0.3f, 0.4f)
        val b = newBitmap(Color.DKGRAY)
        oldExportCompose(Canvas(b), null, Shader.TileMode.CLAMP, Paint(), 200, 200, 0.3f, 0.4f)
        assertArrayEquals(pixels(b), pixels(a))
    }
}
