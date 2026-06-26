package me.rosuh.easywatermark.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * S4d-10 — **on-device text RASTER delta gate (measure-only)**.
 *
 * Renders each watermark text corpus row two ways on a real device and quantifies the difference
 * between the Android production text raster and the commonMain text raster:
 *  - **Android production:** [WatermarkRenderer.buildTextShader] (`StaticLayout.draw`), one cell drawn
 *    from its `BitmapShader` at the origin (the existing cell-capture idiom);
 *  - **commonMain:** [WatermarkCellComposer.composeTextCell] (`MultiParagraph.paint`), via the SAME
 *    system font + SAME measurement env ([androidTextMeasureEnv] → [TextRasterEnv]) and the SAME
 *    [TextPaint]-derived style ([toWatermarkTextStyle]).
 *
 * Why on-device, not JVM: Robolectric NATIVE renders emoji/rotated text BLANK and its CJK metrics
 * differ from a device (ADR-0010 / `WatermarkCellGoldenTest`), so a JVM run cannot be the text raster
 * oracle. This MUST run instrumented.
 *
 * Scope (S4d-9 accepted plan): this is a MEASUREMENT slice, NOT a renderer swap. It touches no
 * production / `:shared` / build / font / golden / UI code. Hard assertions are limited to **exact cell
 * dimensions** (S3b already proves measurement parity) and **non-blank output** on both paths. It does
 * NOT assert byte-equality, IoU, alpha-MAE, or colour-diff thresholds — those numbers are logged
 * (`TEXT-RASTER|…`) for the coordinator to decide whether an Android text draw-swap is viable as-is,
 * needs a bundled font + sign-off, or should stay native.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkTextRasterParityInstrumentedTest {

    private val tag = "TEXT-RASTER"
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun imageInfo() = ImageInfo.empty().apply { width = 1000; height = 1000 }
    private fun deviceKey() = "${Build.MODEL}/api${Build.VERSION.SDK_INT}"

    private data class Row(
        val label: String,
        val text: String,
        val degree: Float,
        val typeface: TextTypeface = TextTypeface.Normal,
    )

    private val corpus = listOf(
        Row("ascii_0", "GOLDEN", 0f),
        Row("ascii_315", "GOLDEN", 315f),
        Row("multiline", "DO NOT\nREDISTRIBUTE", 0f),
        Row("emoji_default_315", "👋 DO NOT REDISTRIBUTE", 315f),
        Row("cjk_0", "你好世界", 0f),
        Row("cjk_315", "你好世界", 315f),
        Row("bold", "GOLDEN", 0f, TextTypeface.Bold),
        Row("italic", "GOLDEN", 0f, TextTypeface.Italic),
        // S4d-15: complete the gap map for the bundled-font/parity-threshold decision.
        Row("bold_italic", "GOLDEN", 0f, TextTypeface.BoldItalic),
        Row("cjk_multiline_0", "请勿转载\n仅供预览", 0f),
    )

    // ---- pixel helpers (mirrors WatermarkRendererCommonParityTest) --------------------------

    private fun alphaOf(c: Int): Int = (c ushr 24) and 0xFF
    private data class Cell(val w: Int, val h: Int, val px: IntArray)

    private fun Bitmap.toCell(): Cell {
        val arr = IntArray(width * height)
        getPixels(arr, 0, width, 0, 0, width, height)
        return Cell(width, height, arr)
    }

    private fun nonTransparent(c: Cell): Int = c.px.count { alphaOf(it) > 0 }

    /** IoU of opaque-pixel footprints; requires equal dims. */
    private fun opaqueIoU(a: Cell, b: Cell): Double {
        var inter = 0; var union = 0
        for (i in a.px.indices) {
            val oa = alphaOf(a.px[i]) > 0; val ob = alphaOf(b.px[i]) > 0
            if (oa || ob) union++
            if (oa && ob) inter++
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    /** (mean-abs-alpha-delta, max-abs-alpha-delta); requires equal dims. */
    private fun alphaDelta(a: Cell, b: Cell): Pair<Double, Int> {
        var sum = 0L; var max = 0
        for (i in a.px.indices) {
            val d = abs(alphaOf(a.px[i]) - alphaOf(b.px[i]))
            sum += d; if (d > max) max = d
        }
        return (sum.toDouble() / a.px.size) to max
    }

    private fun colorDiff(a: Cell, b: Cell): Int {
        var n = 0
        for (i in a.px.indices) if (a.px[i] != b.px[i]) n++
        return n
    }

    // ---- cell builders ----------------------------------------------------------------------

    private fun config(row: Row) = WaterMark.default.copy(
        text = row.text,
        degree = row.degree,
        hGap = 0,
        vGap = 0,
        textSize = 24f,
        textColor = Color.WHITE,
        textTypeface = row.typeface,
        iconUri = MediaRef.Empty,
    )

    /** Android production text cell = one tile of its BitmapShader rendered at the origin. */
    private fun androidCell(row: Row): Cell {
        val info = imageInfo()
        val cfg = config(row)
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        val shader = runBlocking {
            WatermarkRenderer.buildTextShader(info, cfg, paint, androidTextMeasureEnv(ctx), Dispatchers.Unconfined)
        }!!
        val w = shader.width.coerceAtLeast(1); val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return out.toCell()
    }

    /** commonMain text cell via composeTextCell, with the SAME font/measurement env + style. */
    private fun commonCell(row: Row): Cell {
        val info = imageInfo()
        val cfg = config(row)
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        val env = androidTextMeasureEnv(ctx)
        val rasterEnv = TextRasterEnv(env.fontFamilyResolver, env.density, env.layoutDirection)
        val content = WatermarkTextContent(
            text = row.text,
            style = paint.toWatermarkTextStyle(),
            color = androidx.compose.ui.graphics.Color.White,
        )
        val cell = WatermarkCellComposer.composeTextCell(
            rasterEnv, content, degree = cfg.degree, hGapPercent = cfg.hGap, vGapPercent = cfg.vGap,
        )
        return cell.asAndroidBitmap().toCell()
    }

    /**
     * S4d-16 (owner-approved C2, test-only): the bundled Latin + CJK SC [FontFamily], loaded from the
     * androidTest assets (`app/src/androidTest/assets/fonts/`) via the test apk's `AssetManager` — NOT
     * production res/assets, NOT compose-resources. Injected through `WatermarkTextContent.style.fontFamily`
     * across the existing `TextRasterEnv` boundary. Bold/Italic synthesized (no bundled bold/italic faces).
     */
    private fun testAssets(): AssetManager = InstrumentationRegistry.getInstrumentation().context.assets

    /**
     * S4d-16 round 2 (P1): the bundled Latin + CJK SC [FontFamily] for the commonMain side. [latinFirst]
     * lists the Latin face first (owner's Latin+CJK order) or the CJK face first (round-1 order). NOTE a
     * Compose `FontFamily(fontA, fontB)` of the same weight/style does not guarantee per-glyph fallback
     * between the two user fonts — the test logs BOTH orders so the real behaviour is visible.
     */
    private fun bundledFamily(latinFirst: Boolean): FontFamily {
        val latin = Font(assetManager = testAssets(), path = "fonts/NotoSans-Regular.ttf")
        val cjk = Font(assetManager = testAssets(), path = "fonts/NotoSansSC-Regular.otf")
        return if (latinFirst) FontFamily(latin, cjk) else FontFamily(cjk, latin)
    }

    /**
     * S4d-16 round 2 (P1): a real Android [Typeface] from the SAME bundled fonts with a proper per-glyph
     * fallback chain ([Typeface.CustomFallbackBuilder], API 29+): Latin primary + CJK fallback (or
     * reversed). This is what gives the test-only Android `StaticLayout` comparator the same bundled-font
     * strategy as the commonMain side, so the two can be compared at matched dims to isolate the
     * `StaticLayout`-vs-`MultiParagraph` ENGINE delta (not confounded by font choice). Test-only.
     */
    private fun bundledTypeface(latinFirst: Boolean): Typeface {
        val am = testAssets()
        val latinFam = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(am, "fonts/NotoSans-Regular.ttf").build(),
        ).build()
        val cjkFam = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(am, "fonts/NotoSansSC-Regular.otf").build(),
        ).build()
        val primary = if (latinFirst) latinFam else cjkFam
        val fallback = if (latinFirst) cjkFam else latinFam
        return Typeface.CustomFallbackBuilder(primary).addCustomFallback(fallback).build()
    }

    /** commonMain text cell rendered with the BUNDLED font (S4d-16), [latinFirst] order configurable. */
    private fun commonCellBundled(row: Row, latinFirst: Boolean): Cell {
        val info = imageInfo()
        val cfg = config(row)
        val paint = TextPaint().applyConfig(info, cfg, isScale = false)
        val env = androidTextMeasureEnv(ctx)
        val rasterEnv = TextRasterEnv(env.fontFamilyResolver, env.density, env.layoutDirection)
        val content = WatermarkTextContent(
            text = row.text,
            style = paint.toWatermarkTextStyle().copy(fontFamily = bundledFamily(latinFirst)),
            color = androidx.compose.ui.graphics.Color.White,
        )
        return WatermarkCellComposer.composeTextCell(
            rasterEnv, content, degree = cfg.degree, hGapPercent = cfg.hGap, vGapPercent = cfg.vGap,
        ).asAndroidBitmap().toCell()
    }

    /**
     * S4d-16 round 2 (P1): TEST-ONLY Android `StaticLayout` comparator using the SAME bundled fonts
     * ([bundledTypeface]) — replicates the production text path (legacy `StaticLayout` + S4d-14C full-block
     * vertical centring + CENTER `TextPaint`) but with the bundled Typeface, drawn into a cell of the
     * GIVEN [w]x[h] (the commonMain bundled cell's dims) so the two share dims and the comparison isolates
     * the layout-engine raster delta. Does NOT touch production `WatermarkRenderer`. Requires API 29+
     * (for the custom-fallback Typeface); the caller guards on SDK_INT.
     */
    private fun androidBundledCell(row: Row, latinFirst: Boolean, w: Int, h: Int): Cell {
        val info = imageInfo()
        val cfg = config(row)
        // Preserve the requested TextTypeface style on the bundled custom-fallback typeface, mirroring
        // production `Typeface.create(typeface, config.textTypeface.obtainSysTypeface())` (PainKtx.kt) and
        // the common bundled side's `toWatermarkTextStyle()` weight/style. Without this the bold/italic
        // rows would compare styled commonMain vs NORMAL Android, confounding the engine finding.
        // `Typeface.create(family, style)` derives from the family, keeping its custom fallback chain.
        val styledBundled = Typeface.create(bundledTypeface(latinFirst), cfg.textTypeface.obtainSysTypeface())
        val paint = TextPaint().applyConfig(info, cfg, isScale = false).apply { typeface = styledBundled }
        var maxLineWidth = 0
        cfg.text.split("\n").forEach {
            val s = cfg.text.indexOf(it).coerceAtLeast(0)
            maxLineWidth = maxOf(maxLineWidth, paint.measureText(cfg.text, s, (s + it.length).coerceAtMost(cfg.text.length)).toInt())
        }
        val sl = android.text.StaticLayout.Builder
            .obtain(cfg.text, 0, cfg.text.length, paint, maxLineWidth)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .build()
        val cw = w.coerceAtLeast(1); val ch = h.coerceAtLeast(1)
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.rotate(cfg.degree, cw / 2f, ch / 2f)
        canvas.save()
        canvas.translate(cw / 2f, (ch - sl.height) / 2f) // S4d-14C full-block centring
        sl.draw(canvas)
        canvas.restore()
        return out.toCell()
    }

    // ---- the gate ---------------------------------------------------------------------------

    @Test
    fun text_raster_delta_android_vs_commonMain() {
        val dev = deviceKey()
        Log.i(tag, "=== S4d-10 text raster delta gate on $dev (measure-only; thresholds NOT asserted) ===")
        val failures = mutableListOf<String>()

        for (row in corpus) {
            val a = androidCell(row)
            val c = commonCell(row)
            val aNon = nonTransparent(a); val cNon = nonTransparent(c)
            val sameDims = a.w == c.w && a.h == c.h
            val iou = if (sameDims) opaqueIoU(a, c) else -1.0
            val (mae, maxD) = if (sameDims) alphaDelta(a, c) else (-1.0 to -1)
            val cdiff = if (sameDims) colorDiff(a, c) else -1
            val total = a.w * a.h

            // Full metrics line — logged for EVERY row before any assertion, so logcat captures the
            // complete table even if a row fails the hard dims/nonblank checks.
            Log.i(
                tag,
                "TEXT-RASTER|label=${row.label}|device=$dev|android=${a.w}x${a.h}|common=${c.w}x${c.h}" +
                    "|androidNonBlank=$aNon|commonNonBlank=$cNon|iou=$iou|alphaMae=$mae" +
                    "|maxAlphaDelta=$maxD|colorDiff=$cdiff/$total",
            )

            if (aNon <= 0) failures += "${row.label}: android cell blank"
            if (cNon <= 0) failures += "${row.label}: common cell blank"
            if (!sameDims) failures += "${row.label}: dims differ android=${a.w}x${a.h} common=${c.w}x${c.h}"
        }

        // Hard assertions for THIS slice only: every row nonblank on both paths + exact dims.
        assertTrue("text raster gate failures: $failures", failures.isEmpty())
    }

    /**
     * S4d-16 bundled-font re-measurement (measure-only). For each corpus row, renders the commonMain
     * cell with the BUNDLED Noto Sans SC font and logs two deltas:
     *  - `bundledVsAndroid` — bundled commonMain vs the production Android `StaticLayout` (system font):
     *    the delta a FUTURE bundled-font text draw-swap would have vs current production.
     *  - `bundledVsCommonSys` — bundled vs system-font commonMain: whether bundling changes the common
     *    side at all on-device (on a device the system CJK font is already Noto CJK, so for CJK this is
     *    expected to be near-zero — confirming S4d-15's finding that the Android CJK gap is the
     *    `StaticLayout`-vs-`MultiParagraph` ENGINE, not the font).
     * IoU/colorDiff are computed only when dims match; the bundled font may measure a different cell box
     * than the system font (Noto Sans SC metrics ≠ system), which is LOGGED, not gated. Hard assertion:
     * the bundled cell renders non-blank. No threshold gating; no production wiring; CJK stays LOG-ONLY.
     */
    @Test
    fun text_raster_delta_bundled_font_S4d16() {
        val dev = deviceKey()
        val canBundleAndroid = Build.VERSION.SDK_INT >= 29 // Typeface.CustomFallbackBuilder
        Log.i(tag, "=== S4d-16 r2 bundled-font text raster delta on $dev (measure-only; androidBundled=$canBundleAndroid) ===")
        val failures = mutableListOf<String>()
        for (row in corpus) {
            val aSys = androidCell(row)                       // production: StaticLayout + system font
            val cSys = commonCell(row)                        // commonMain MultiParagraph + system font
            val cLF = commonCellBundled(row, latinFirst = true)   // commonMain + bundled (Latin-first)
            val cCF = commonCellBundled(row, latinFirst = false)  // commonMain + bundled (CJK-first, r1 order)

            // P1a — does Latin-first restore ~system dims, and does CJK still render? (fallback behaviour)
            Log.i(
                tag,
                "TEXT-RASTER-BUNDLED-ORDER|label=${row.label}|device=$dev|system=${aSys.w}x${aSys.h}" +
                    "|commonLatinFirst=${cLF.w}x${cLF.h}|nbLatinFirst=${nonTransparent(cLF)}" +
                    "|commonCjkFirst=${cCF.w}x${cCF.h}|nbCjkFirst=${nonTransparent(cCF)}",
            )

            // P1b — engine delta at MATCHED dims: commonBundledLatinFirst vs androidBundledLatinFirst
            // (same bundled fonts both sides, android via StaticLayout + custom-fallback Typeface).
            if (canBundleAndroid) {
                val aLF = androidBundledCell(row, latinFirst = true, w = cLF.w, h = cLF.h)
                // Engine delta: cLF vs aLF — both intentionally at cLF dims, so the denominator is the
                // compared pair's area (NOT the system cell's area). aLF is built at cLF.w x cLF.h.
                val dimsEng = cLF.w == aLF.w && cLF.h == aLF.h
                val engTotal = cLF.w * cLF.h
                val iouEngine = if (dimsEng) opaqueIoU(cLF, aLF) else -1.0
                val cdiffEngine = if (dimsEng) "${colorDiff(cLF, aLF)}/$engTotal" else "na"
                // Swap delta: commonBundledLatinFirst vs existing production/system Android cell. The
                // bundled font shifts dims off the system cell, so this is usually dims-mismatched →
                // log an explicit `na` denominator rather than a misleading area.
                val dimsSwap = cLF.w == aSys.w && cLF.h == aSys.h
                val iouSwap = if (dimsSwap) opaqueIoU(cLF, aSys) else -1.0
                val cdiffSwap = if (dimsSwap) "${colorDiff(cLF, aSys)}/${aSys.w * aSys.h}" else "na"
                Log.i(
                    tag,
                    "TEXT-RASTER-BUNDLED|label=${row.label}|device=$dev|commonLF=${cLF.w}x${cLF.h}" +
                        "|androidLF=${aLF.w}x${aLF.h}|androidSys=${aSys.w}x${aSys.h}" +
                        "|androidBundledNonBlank=${nonTransparent(aLF)}" +
                        "|iou_commonLFvsAndroidLF=$iouEngine|colorDiff_commonLFvsAndroidLF=$cdiffEngine" +
                        "|iou_commonLFvsAndroidSys=$iouSwap|colorDiff_commonLFvsAndroidSys=$cdiffSwap",
                )
                if (nonTransparent(aLF) <= 0) failures += "${row.label}: android bundled cell blank"
            } else {
                Log.i(tag, "TEXT-RASTER-BUNDLED|label=${row.label}|device=$dev|androidBundled=SKIPPED(api<29)")
            }

            if (nonTransparent(cLF) <= 0) failures += "${row.label}: common bundled (latin-first) cell blank"
            if (nonTransparent(cCF) <= 0) failures += "${row.label}: common bundled (cjk-first) cell blank"
        }
        assertTrue("bundled-font measurement failures: $failures", failures.isEmpty())
    }
}
