package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.TextPaint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.utils.ktx.applyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * S4d-5: the first **test-only Android-vs-commonMain renderer parity gate**, before any production
 * draw-swap (CMP plan C2). It compares the production Android renderer seam
 * ([WatermarkRenderer.buildIconShader] / [WatermarkRenderer.buildTextShader]) against the new
 * commonMain primitives ([WatermarkCellComposer.composeIconCell] / [WatermarkCellComposer.composeTextCell]).
 *
 * Why this is meaningful on the JVM: under Robolectric `@GraphicsMode(NATIVE)` BOTH paths rasterize
 * through the SAME backend — the Android renderer via `android.graphics`, and the commonMain composer
 * (compiled into `:shared`'s android target, consumed by `:app`) via `androidx.compose.ui.graphics`,
 * whose Android actuals ARE `android.graphics`. So a residual diff is attributable to the two code
 * paths, not to two Skia builds (a Skiko-vs-Android comparison would conflate them).
 *
 * HARD gates: cell dimensions equal exactly (icon + simple-ASCII text), both render non-blank, alpha
 * parity (commonMain `alpha=config.alpha/255f` reproduces Android `Paint.alpha=config.alpha`), and
 * centering structure. TOLERANCE gate: opaque-footprint IoU (tolerant of the documented sub-pixel
 * pivot/scale/offset deltas). NOT asserted: exact icon pixels and any text RASTER parity (StaticLayout
 * vs MultiParagraph; Robolectric NATIVE renders emoji/rotated text blank — see WatermarkCellGoldenTest).
 * This gate touches NO production code; preview/export still use [WatermarkRenderer].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // plain app — avoids MyApp.startKoin double-start across the suite
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkRendererCommonParityTest {

    private val imageInfo get() = ImageInfo.empty().apply { width = 1000; height = 1000 }

    // ---- pixel helpers ---------------------------------------------------------------------

    private fun alphaOf(c: Int): Int = (c ushr 24) and 0xFF

    private data class Cell(val w: Int, val h: Int, val px: IntArray)

    private fun Bitmap.toCell(): Cell {
        val arr = IntArray(width * height)
        getPixels(arr, 0, width, 0, 0, width, height)
        return Cell(width, height, arr)
    }

    /** Render ONE Android icon cell (the cell == one tile of its BitmapShader at origin). */
    private fun androidIconCell(src: Bitmap, config: WaterMark, alpha255: Int): Cell {
        val paint = Paint().apply { alpha = alpha255 }
        val shader = runBlocking {
            WatermarkRenderer.buildIconShader(imageInfo, src, config, paint, false, Dispatchers.Unconfined)
        }
        assertNotNull("android icon shader must build", shader)
        val w = shader!!.width.coerceAtLeast(1)
        val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return out.toCell()
    }

    /** Render ONE commonMain icon cell via the composer, read back through android Bitmap. */
    private fun commonIconCell(src: Bitmap, config: WaterMark, alpha255: Int): Cell {
        val cell = WatermarkCellComposer.composeIconCell(
            icon = src.asImageBitmap(),
            degree = config.degree,
            hGapPercent = config.hGap,
            vGapPercent = config.vGap,
            scaleRatio = config.textSize / WatermarkCellComposer.ICON_SCALE_REFERENCE_TEXT_SIZE,
            alpha = alpha255 / 255f,
        )
        return cell.asAndroidBitmap().toCell()
    }

    private fun nonTransparent(c: Cell): Int = c.px.count { alphaOf(it) > 0 }

    private fun meanAlphaOverOpaque(c: Cell): Double {
        val opaque = c.px.filter { alphaOf(it) > 0 }
        if (opaque.isEmpty()) return 0.0
        return opaque.sumOf { alphaOf(it) }.toDouble() / opaque.size
    }

    /** IoU of opaque-pixel footprints (same dims required). Tolerant of sub-pixel placement deltas. */
    private fun opaqueIoU(a: Cell, b: Cell): Double {
        require(a.w == b.w && a.h == b.h)
        var inter = 0; var union = 0
        for (i in a.px.indices) {
            val oa = alphaOf(a.px[i]) > 0
            val ob = alphaOf(b.px[i]) > 0
            if (oa || ob) union++
            if (oa && ob) inter++
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun solidIcon(w: Int, h: Int, color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun iconConfig(degree: Float, hGap: Int, vGap: Int, textSize: Float): WaterMark =
        WaterMark.default.copy(degree = degree, hGap = hGap, vGap = vGap, textSize = textSize, iconUri = MediaRef.Empty)

    // ---- ICON: hard dimension gate --------------------------------------------------------

    @Test
    fun iconCell_dimensions_match_exactly() {
        val src = solidIcon(40, 20)
        val cases = listOf(
            Triple(0f, 0, 0), Triple(45f, 0, 0), Triple(90f, 0, 0),
            Triple(0f, 50, 50), Triple(0f, 100, 100), Triple(30f, 100, 50),
        )
        val scales = listOf(7f, 14f, 28f) // scaleRatio 0.5, 1, 2
        for ((deg, hg, vg) in cases) for (ts in scales) {
            val config = iconConfig(deg, hg, vg, ts)
            val a = androidIconCell(src, config, 255)
            val c = commonIconCell(src, config, 255)
            println("ICON-DIM deg=$deg gap=$hg/$vg ts=$ts android=${a.w}x${a.h} common=${c.w}x${c.h}")
            assertEquals("icon cell width parity (deg=$deg gap=$hg/$vg ts=$ts)", a.w, c.w)
            assertEquals("icon cell height parity (deg=$deg gap=$hg/$vg ts=$ts)", a.h, c.h)
        }
    }

    // ---- ICON: nonblank + opaque-footprint IoU --------------------------------------------

    @Test
    fun iconCell_nonblank_and_footprint_overlaps() {
        val src = solidIcon(40, 20)
        for (deg in listOf(0f, 45f, 90f)) {
            val config = iconConfig(deg, 0, 0, 14f)
            val a = androidIconCell(src, config, 255)
            val c = commonIconCell(src, config, 255)
            val iou = opaqueIoU(a, c)
            println("ICON-IOU deg=$deg android.opaque=${nonTransparent(a)} common.opaque=${nonTransparent(c)} iou=$iou")
            assertTrue("android icon cell must render visible pixels (deg=$deg)", nonTransparent(a) > 0)
            assertTrue("common icon cell must render visible pixels (deg=$deg)", nonTransparent(c) > 0)
            // Measured IoU is 1.0 (exact opaque-footprint match) for solid icons @ deg 0/45/90 under
            // Robolectric NATIVE; the 0.95 floor leaves headroom for sub-pixel AA jitter on rotated
            // edges (the documented pivot/offset deltas) without being brittle.
            assertTrue("opaque-footprint IoU must be near-exact (deg=$deg, was $iou)", iou >= 0.95)
        }
    }

    // ---- ICON: alpha parity ----------------------------------------------------------------

    @Test
    fun iconCell_alpha_parity() {
        val src = solidIcon(40, 20)
        val config = iconConfig(0f, 0, 0, 14f)
        val aFull = androidIconCell(src, config, 255)
        val cFull = commonIconCell(src, config, 255)
        val aHalf = androidIconCell(src, config, 128)
        val cHalf = commonIconCell(src, config, 128)
        val aFullMean = meanAlphaOverOpaque(aFull); val aHalfMean = meanAlphaOverOpaque(aHalf)
        val cFullMean = meanAlphaOverOpaque(cFull); val cHalfMean = meanAlphaOverOpaque(cHalf)
        println("ICON-ALPHA android full=$aFullMean half=$aHalfMean | common full=$cFullMean half=$cHalfMean")

        // Both paths: half-alpha roughly halves the mean opacity (128/255 ≈ 0.502).
        assertTrue("android alpha=128 must roughly halve opacity", aHalfMean in (aFullMean * 0.40)..(aFullMean * 0.60))
        assertTrue("common alpha=0.5 must roughly halve opacity", cHalfMean in (cFullMean * 0.40)..(cFullMean * 0.60))
        // The two paths agree on the resulting opacity. Measured delta is 0.0 (both 255/255 and
        // 128/128) for the deg=0 solid icon (no AA edges); ≤2.0 keeps the gate meaningful with a
        // hair of headroom rather than asserting brittle exact float equality.
        assertTrue("full-opacity mean alpha parity (a=$aFullMean c=$cFullMean)", Math.abs(aFullMean - cFullMean) <= 2.0)
        assertTrue("half-opacity mean alpha parity (a=$aHalfMean c=$cHalfMean)", Math.abs(aHalfMean - cHalfMean) <= 2.0)
    }

    // ---- ICON: centering structure ---------------------------------------------------------

    @Test
    fun iconCell_centering_structure() {
        val src = solidIcon(40, 20)
        val config = iconConfig(0f, 100, 100, 14f) // big gap → cell larger than icon → transparent margins
        val a = androidIconCell(src, config, 255)
        val c = commonIconCell(src, config, 255)
        for ((name, cell) in listOf("android" to a, "common" to c)) {
            assertTrue("$name corner must be transparent (icon centred)", alphaOf(cell.px[0]) == 0)
            val centre = cell.px[(cell.h / 2) * cell.w + (cell.w / 2)]
            assertTrue("$name centre must be opaque (icon centred)", alphaOf(centre) > 0)
        }
    }

    // ---- TEXT: hard dimension gate (cell BOX parity; raster parity is a later gate) --------

    private fun textShaderDims(text: String, degree: Float, hGap: Int, vGap: Int): Pair<Int, Int> {
        val config = WaterMark.default.copy(
            text = text, degree = degree, hGap = hGap, vGap = vGap, textSize = 24f, iconUri = MediaRef.Empty,
        )
        val ctx = RuntimeEnvironment.getApplication()
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val shader = runBlocking {
            WatermarkRenderer.buildTextShader(imageInfo, config, paint, androidTextMeasureEnv(ctx), Dispatchers.Unconfined)
        }
        assertNotNull("text shader must build", shader)
        return shader!!.width to shader.height
    }

    private fun commonTextCellDims(text: String, degree: Float, hGap: Int, vGap: Int): Pair<Int, Int> {
        val ctx = RuntimeEnvironment.getApplication()
        val config = WaterMark.default.copy(text = text, textSize = 24f, iconUri = MediaRef.Empty)
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val env = androidTextMeasureEnv(ctx)
        val rasterEnv = TextRasterEnv(env.fontFamilyResolver, env.density, env.layoutDirection)
        val content = WatermarkTextContent(text = text, style = paint.toWatermarkTextStyle(), color = androidx.compose.ui.graphics.Color.White)
        val cell = WatermarkCellComposer.composeTextCell(rasterEnv, content, degree = degree, hGapPercent = hGap, vGapPercent = vGap)
        return cell.width to cell.height
    }

    @Test
    fun textCell_dimensions_match_exactly_simple_ascii() {
        for ((deg, hg, vg) in listOf(Triple(0f, 0, 0), Triple(90f, 0, 0), Triple(0f, 100, 100))) {
            val (aw, ah) = textShaderDims("GOLDEN", deg, hg, vg)
            val (cw, ch) = commonTextCellDims("GOLDEN", deg, hg, vg)
            println("TEXT-DIM deg=$deg gap=$hg/$vg android=${aw}x${ah} common=${cw}x${ch}")
            assertEquals("text cell width parity (deg=$deg gap=$hg/$vg)", aw, cw)
            assertEquals("text cell height parity (deg=$deg gap=$hg/$vg)", ah, ch)
        }
    }

    @Test
    fun textCell_both_render_nonblank_simple_ascii_degree0() {
        // Simple ASCII @ degree 0 — the case Robolectric NATIVE can raster (emoji/rotated is blank,
        // see WatermarkCellGoldenTest). Text RASTER parity (StaticLayout vs MultiParagraph) is NOT
        // asserted; only that neither path produces an empty cell for the safe case.
        val config = WaterMark.default.copy(text = "GOLDEN", textSize = 24f, textColor = Color.WHITE, iconUri = MediaRef.Empty)
        val ctx = RuntimeEnvironment.getApplication()
        val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
        val shader = runBlocking {
            WatermarkRenderer.buildTextShader(imageInfo, config, paint, androidTextMeasureEnv(ctx), Dispatchers.Unconfined)
        }!!
        val w = shader.width; val h = shader.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        val androidNonBlank = out.toCell().let(::nonTransparent)

        val env = androidTextMeasureEnv(ctx)
        val rasterEnv = TextRasterEnv(env.fontFamilyResolver, env.density, env.layoutDirection)
        val content = WatermarkTextContent("GOLDEN", paint.toWatermarkTextStyle(), androidx.compose.ui.graphics.Color.White)
        val commonNonBlank = WatermarkCellComposer.composeTextCell(rasterEnv, content, degree = 0f)
            .asAndroidBitmap().toCell().let(::nonTransparent)
        println("TEXT-NONBLANK android=$androidNonBlank common=$commonNonBlank")
        assertTrue("android text cell must render visible pixels (simple ASCII @0)", androidNonBlank > 0)
        assertTrue("common text cell must render visible pixels (simple ASCII @0)", commonNonBlank > 0)
    }
}
