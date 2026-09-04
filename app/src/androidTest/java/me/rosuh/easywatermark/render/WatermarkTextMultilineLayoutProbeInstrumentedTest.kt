package me.rosuh.easywatermark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * S4d-11 — **multiline text layout root-cause probe (diagnostic only)**.
 *
 * S4d-10 found that multiline text has EXACT cell dimensions but a huge raster gap
 * (IoU 0.11; commonMain painted 2167 opaque px vs Android 1635). This probe dumps, on-device, the
 * Android `StaticLayout` layout model + the Compose `TextMeasurer`/`MultiParagraph` layout model for
 * the same rows, the EXACT two centring translations each renderer uses, and the rendered ink
 * geometry (bbox, per-band horizontal centre, bottom-edge clipping) — to localize the cause to
 * horizontal per-line alignment vs vertical placement vs leading, grounded in source + numbers.
 *
 * It implements NO renderer fix and changes NO production / `:shared` code. It reconstructs both
 * layout models in-test, exactly as `WatermarkRenderer.buildTextShader` and
 * `WatermarkCellComposer.composeTextCell` build them, then renders the production cells for ink
 * geometry. On-device (not Robolectric): the corpus includes CJK/rotation that Robolectric renders
 * blank. Hard assertions are limited to nonblank + exact dims; everything else is logged (`MULTILINE-…`).
 */
@RunWith(AndroidJUnit4::class)
class WatermarkTextMultilineLayoutProbeInstrumentedTest {

    private val tag = "MULTILINE-ROOT"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun imageInfo() = ImageInfo.empty().apply { width = 1000; height = 1000 }
    private fun dev() = "${Build.MODEL}/api${Build.VERSION.SDK_INT}"

    private data class Row(val label: String, val text: String, val degree: Float)

    private val corpus = listOf(
        Row("multiline_0", "DO NOT\nREDISTRIBUTE", 0f),     // primary
        Row("ascii_0_control", "GOLDEN", 0f),               // single-line control (S4d-10: byte-identical)
        Row("multiline_315", "DO NOT\nREDISTRIBUTE", 315f),
        Row("cjk_multiline_0", "请勿转载\n仅供预览", 0f),
    )

    private fun config(row: Row) = WaterMark.default.copy(
        text = row.text, degree = row.degree, hGap = 0, vGap = 0,
        textSize = 24f, textColor = Color.WHITE, iconUri = MediaRef.Empty,
    )

    // ---- pixel helpers ----------------------------------------------------------------------

    private fun alphaOf(c: Int) = (c ushr 24) and 0xFF
    private class Cell(val w: Int, val h: Int, val px: IntArray)

    private fun Bitmap.toCell(): Cell {
        val arr = IntArray(width * height); getPixels(arr, 0, width, 0, 0, width, height)
        return Cell(width, height, arr)
    }

    private fun opaque(c: Cell) = c.px.count { alphaOf(it) > 0 }

    /** alpha bounding box minX,minY,maxX,maxY (or -1s if blank). */
    private fun bbox(c: Cell): IntArray {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until c.h) for (x in 0 until c.w) if (alphaOf(c.px[y * c.w + x]) > 0) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        return if (maxX < 0) intArrayOf(-1, -1, -1, -1) else intArrayOf(minX, minY, maxX, maxY)
    }

    /** Opaque horizontal centre (minX..maxX midpoint) within rows [y0,y1). -1 if no ink in band. */
    private fun bandCentreX(c: Cell, y0: Int, y1: Int): Int {
        var minX = Int.MAX_VALUE; var maxX = -1
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(c.h)) for (x in 0 until c.w)
            if (alphaOf(c.px[y * c.w + x]) > 0) { if (x < minX) minX = x; if (x > maxX) maxX = x }
        return if (maxX < 0) -1 else (minX + maxX) / 2
    }

    /** Opaque count in the bottom edge row (clipping indicator). */
    private fun bottomRowOpaque(c: Cell): Int {
        var n = 0; val y = c.h - 1
        for (x in 0 until c.w) if (alphaOf(c.px[y * c.w + x]) > 0) n++
        return n
    }

    // ---- production cell renderers (exactly as the two seams build them) --------------------

    private fun androidCell(row: Row): Cell {
        val info = imageInfo(); val cfg = config(row)
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        val shader = runBlocking {
            WatermarkRenderer.buildTextShader(info, cfg, paint, androidTextMeasureEnv(ctx), Dispatchers.Unconfined)
        }!!
        val w = shader.width.coerceAtLeast(1); val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return out.toCell()
    }

    private fun commonCell(row: Row): Cell {
        val info = imageInfo(); val cfg = config(row)
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        val env = androidTextMeasureEnv(ctx)
        val rasterEnv = TextRasterEnv(env.fontFamilyResolver, env.density, env.layoutDirection)
        val content = WatermarkTextContent(row.text, paint.toWatermarkTextStyle(), androidx.compose.ui.graphics.Color.White)
        return WatermarkCellComposer.composeTextCell(
            rasterEnv, content, degree = cfg.degree, hGapPercent = cfg.hGap, vGapPercent = cfg.vGap,
        ).asAndroidBitmap().toCell()
    }

    // ---- layout-model reconstruction (mirrors each seam) ------------------------------------

    /** Rebuild Android StaticLayout exactly as buildTextShader does, and log its line metrics. */
    private fun dumpAndroidLayout(row: Row, finalW: Int, finalH: Int) {
        val info = imageInfo(); val cfg = config(row)
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        var maxLineWidth = 0
        cfg.text.split("\n").forEach {
            val s = cfg.text.indexOf(it).coerceAtLeast(0)
            maxLineWidth = max(maxLineWidth, paint.measureText(cfg.text, s, (s + it.length).coerceAtMost(cfg.text.length)).toInt())
        }
        val sl = StaticLayout.Builder.obtain(cfg.text, 0, cfg.text.length, paint, maxLineWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        // The EXACT vertical translate buildTextShader uses + horizontal origin. S4d-14C: the renderer
        // now centres the FULL block `(finalH - sl.height)/2` (was line-0-based); this log mirrors that.
        val yOffset = (finalH - sl.height) / 2f
        val xOrigin = finalW / 2f
        Log.i(tag, "MULTILINE-ANDROID|label=${row.label}|paintAlign=${paint.textAlign}|maxLineWidth=$maxLineWidth" +
            "|slW=${sl.width}|slH=${sl.height}|lineCount=${sl.lineCount}|xOrigin=$xOrigin|yOffset=$yOffset")
        for (i in 0 until sl.lineCount) {
            Log.i(tag, "MULTILINE-ANDROID-LINE|label=${row.label}|i=$i|top=${sl.getLineTop(i)}|bottom=${sl.getLineBottom(i)}" +
                "|baseline=${sl.getLineBaseline(i)}|ascent=${sl.getLineAscent(i)}|descent=${sl.getLineDescent(i)}" +
                "|left=${sl.getLineLeft(i)}|right=${sl.getLineRight(i)}|width=${sl.getLineWidth(i)}")
        }
    }

    /** Rebuild the Compose TextMeasurer layout exactly as composeTextCell does, and log line metrics. */
    private fun dumpComposeLayout(row: Row, finalW: Int, finalH: Int) {
        val cfg = config(row)
        val info = imageInfo()
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        val env = androidTextMeasureEnv(ctx)
        // S4d-12: mirror composeTextCell's measurement (TextAlign.Center ONLY when lineCount > 1) so
        // this dump matches the fixed cell.
        val measurer = TextMeasurer(env.fontFamilyResolver, env.density, env.layoutDirection)
        val base = paint.toWatermarkTextStyle()
        val initial = measurer.measure(AnnotatedString(row.text), style = base)
        val layout = if (initial.lineCount > 1) {
            measurer.measure(AnnotatedString(row.text), style = base.copy(textAlign = TextAlign.Center))
        } else {
            initial
        }
        val textW = layout.size.width.toFloat().coerceAtLeast(1f)
        val textH = layout.size.height.toFloat().coerceAtLeast(1f)
        // The EXACT box-centring translate composeTextCell uses.
        val xOffset = (finalW - textW) / 2f
        val yOffset = (finalH - textH) / 2f
        Log.i(tag, "MULTILINE-COMPOSE|label=${row.label}|sizeW=${layout.size.width}|sizeH=${layout.size.height}" +
            "|lineCount=${layout.lineCount}|firstBaseline=${layout.firstBaseline}|lastBaseline=${layout.lastBaseline}" +
            "|xOffset=$xOffset|yOffset=$yOffset")
        for (i in 0 until layout.lineCount) {
            Log.i(tag, "MULTILINE-COMPOSE-LINE|label=${row.label}|i=$i|top=${layout.getLineTop(i)}|bottom=${layout.getLineBottom(i)}" +
                "|left=${layout.getLineLeft(i)}|right=${layout.getLineRight(i)}")
        }
    }

    // ---- the probe --------------------------------------------------------------------------

    @Test
    fun multiline_layout_root_cause_probe() {
        val d = dev()
        Log.i(tag, "=== S4d-11 multiline layout root-cause probe on $d (diagnostic; thresholds NOT asserted) ===")
        val failures = mutableListOf<String>()

        for (row in corpus) {
            val a = androidCell(row); val c = commonCell(row)
            val sameDims = a.w == c.w && a.h == c.h
            val ab = bbox(a); val cb = bbox(c)
            // For multiline, compare the top half vs bottom half horizontal centres to expose per-line
            // alignment (Android centres each line; commonMain left-aligns each line in the box).
            val aTopCx = bandCentreX(a, 0, a.h / 2); val aBotCx = bandCentreX(a, a.h / 2, a.h)
            val cTopCx = bandCentreX(c, 0, c.h / 2); val cBotCx = bandCentreX(c, c.h / 2, c.h)

            Log.i(tag, "MULTILINE-ROOT|label=${row.label}|device=$d|android=${a.w}x${a.h}|common=${c.w}x${c.h}" +
                "|androidOpaque=${opaque(a)}|commonOpaque=${opaque(c)}" +
                "|androidBBox=${ab.joinToString(",")}|commonBBox=${cb.joinToString(",")}" +
                "|androidBandCx(top/bot)=$aTopCx/$aBotCx|commonBandCx(top/bot)=$cTopCx/$cBotCx" +
                "|androidBottomRowOpaque=${bottomRowOpaque(a)}|commonBottomRowOpaque=${bottomRowOpaque(c)}")

            // Layout-model dumps reuse the production finalW/finalH (cells share dims; use android's).
            dumpAndroidLayout(row, a.w, a.h)
            dumpComposeLayout(row, a.w, a.h)

            if (opaque(a) <= 0) failures += "${row.label}: android blank"
            if (opaque(c) <= 0) failures += "${row.label}: common blank"
            if (!sameDims) failures += "${row.label}: dims differ ${a.w}x${a.h} vs ${c.w}x${c.h}"
        }
        assertTrue("probe invariant failures: $failures", failures.isEmpty())
    }
}
