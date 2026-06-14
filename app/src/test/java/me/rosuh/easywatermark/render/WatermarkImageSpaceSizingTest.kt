package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.ui.widget.utils.WaterMarkShader
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S3a — image-space `textSize` behavior. `textPx = textSize * imageInfo.width / REF_WIDTH`
 * (`WatermarkRenderer.REF_WIDTH = 1000`). These tests pin the NEW behavior and **fail on the old
 * view-scale-dependent behavior**:
 *  - old text: `textSize` raw (preview) / `textSize * imageInfo.scaleX` (export) — depended on the
 *    preview matrix and ignored image width;
 *  - old icon: `(scale ? imageInfo.scaleX : 1) * textSize/14` — depended on view scale at export.
 *
 * The new behavior is independent of `imageInfo.scaleX` and of the `isScale`/`scale` flags, and scales
 * linearly with `imageInfo.width`. At the reference width (1000, used by every existing golden) the
 * text paint size equals the legacy unscaled size, so existing goldens are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkImageSpaceSizingTest {

    private fun config(size: Float = 24f) = WaterMark.default.copy(
        text = "GOLDEN", degree = 0f, hGap = 0, vGap = 0,
        textSize = size, textColor = Color.WHITE, iconUri = Uri.EMPTY,
    )

    private fun imageInfo(width: Int, scaleX: Float = 1f) =
        ImageInfo.empty().apply { this.width = width; this.height = width; this.scaleX = scaleX }

    // ---- direct paint sizing (the precise old-vs-new pin) --------------------------------------

    @Test
    fun text_paint_size_equals_legacy_at_reference_width() {
        // At REF_WIDTH=1000 the image-space size reproduces the legacy unscaled paint size exactly.
        val p = TextPaint().applyConfig(imageInfo(width = 1000), config(size = 24f), isScale = false)
        assertEquals(24f, p.textSize, 0.001f)
    }

    @Test
    fun text_paint_size_scales_linearly_with_image_width() {
        // 2x image width -> 2x text px. (OLD isScale=true ignored width -> would stay 24f -> fails.)
        val p1000 = TextPaint().applyConfig(imageInfo(1000), config(24f), isScale = true)
        val p2000 = TextPaint().applyConfig(imageInfo(2000), config(24f), isScale = true)
        val p500 = TextPaint().applyConfig(imageInfo(500), config(24f), isScale = true)
        assertEquals(24f, p1000.textSize, 0.001f)
        assertEquals(48f, p2000.textSize, 0.001f)
        assertEquals(12f, p500.textSize, 0.001f)
    }

    @Test
    fun text_paint_size_independent_of_view_scaleX() {
        // OLD export path multiplied by imageInfo.scaleX; new path must ignore it.
        val a = TextPaint().applyConfig(imageInfo(1000, scaleX = 1f), config(24f), isScale = false)
        val b = TextPaint().applyConfig(imageInfo(1000, scaleX = 4f), config(24f), isScale = false)
        assertEquals(a.textSize, b.textSize, 0.001f)
        assertEquals(24f, b.textSize, 0.001f) // not 24*4
    }

    @Test
    fun text_paint_size_independent_of_isScale_flag() {
        // Preview (isScale=true) and export (isScale=false) now use the SAME formula.
        val preview = TextPaint().applyConfig(imageInfo(2000, scaleX = 3f), config(24f), isScale = true)
        val export = TextPaint().applyConfig(imageInfo(2000, scaleX = 3f), config(24f), isScale = false)
        assertEquals(preview.textSize, export.textSize, 0.001f)
        assertEquals(48f, export.textSize, 0.001f) // 24 * 2000/1000
    }

    // ---- rendered text cell end-to-end --------------------------------------------------------

    private fun textCell(width: Int, scaleX: Float, size: Float = 24f): WaterMarkShader {
        val info = imageInfo(width, scaleX)
        val paint = TextPaint().applyConfig(info, config(size), isScale = false)
        return runBlocking {
            WatermarkRenderer.buildTextShader(
                info, config(size), paint,
                androidTextMeasureEnv(RuntimeEnvironment.getApplication()), Dispatchers.Unconfined,
            )
        }!!
    }

    @Test
    fun text_cell_grows_with_image_width() {
        val c1000 = textCell(width = 1000, scaleX = 1f)
        val c2000 = textCell(width = 2000, scaleX = 1f)
        // ~2x paint size -> ~2x cell (allow rounding slack from .toInt()/StaticLayout).
        assertTrue("2000px-image cell must be larger", c2000.width > c1000.width && c2000.height > c1000.height)
        val ratio = c2000.width.toFloat() / c1000.width
        assertTrue("cell width ratio ~2x (was $ratio)", ratio in 1.8f..2.2f)
    }

    @Test
    fun text_cell_independent_of_view_scaleX() {
        val a = textCell(width = 1000, scaleX = 1f)
        val b = textCell(width = 1000, scaleX = 5f)
        assertEquals("cell width must not depend on scaleX", a.width, b.width)
        assertEquals("cell height must not depend on scaleX", a.height, b.height)
    }

    // ---- icon cell ----------------------------------------------------------------------------

    private fun iconCell(scale: Boolean, scaleX: Float, size: Float = 14f): WaterMarkShader {
        val info = imageInfo(1000, scaleX)
        val src = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return runBlocking {
            WatermarkRenderer.buildIconShader(info, src, config(size), Paint(), scale, Dispatchers.Unconfined)
        }!!
    }

    @Test
    fun icon_cell_independent_of_view_scale_and_scaleX() {
        // OLD: scale=true multiplied by imageInfo.scaleX -> bigger at export. NEW: textSize/14 always.
        val previewLike = iconCell(scale = false, scaleX = 1f)
        val exportLike = iconCell(scale = true, scaleX = 4f)
        assertEquals("icon cell must not depend on scale flag/scaleX", previewLike.width, exportLike.width)
        assertEquals("icon cell must not depend on scale flag/scaleX", previewLike.height, exportLike.height)
    }

    @Test
    fun icon_cell_preserves_textsize_over_14_ratio() {
        // textSize 28 -> 2x of textSize 14 (ratio semantics preserved).
        val x1 = iconCell(scale = false, scaleX = 1f, size = 14f)
        val x2 = iconCell(scale = false, scaleX = 1f, size = 28f)
        val ratio = x2.width.toFloat() / x1.width
        assertTrue("icon ratio ~2x (was $ratio)", ratio in 1.8f..2.2f)
    }
}
