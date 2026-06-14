package me.rosuh.easywatermark.render

import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * C2b **candidate signed-baseline** measurement gate. NOT product code; not wired into
 * `WaterMarkImageView`/`MainViewModel`.
 *
 * The candidate `TextMeasurer` measurement now lives behind the extracted seam
 * [WatermarkTextMeasurer] + [TextMeasureEnv] (app module `render/`, ACSP 20260614-002242) instead of
 * inline here — the gate exercises the same seam the future C2b renderer will use. Signed-baseline
 * behavior is unchanged: non-CJK strict legacy==seam (width AND height); CJK exact width + exact signed
 * delta + exact signed absolute baseline (no tolerance widening; every row logged `PARITYGATE|…`).
 *
 * CANDIDATE STATUS — the CJK baselines encode "Option 1" (accept Compose CJK line-height as the C2b
 * future-renderer baseline), pending coordinator/product sign-off (ADR-0014 candidate note). No product
 * renderer/export is wired.
 *
 * PLATFORM-PINNED (ADR-0010): the absolute baselines below are a same-platform device baseline (Samsung
 * SM-S906E / Android 16 / API 36 CJK font). Robolectric is not a CJK dimension oracle; this gate is
 * instrumented/device-only. The CJK *absolute* assertions are device-pinned and only pass on that
 * baseline device — the legacy==seam *parity* + signed *delta* assertions are device-independent.
 * Re-baseline on the pinned CI device if the device fleet changes.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkCellParityGateTest {

    private val tag = "PARITYGATE"

    /**
     * One gate row. `seamW`/`seamH` = the signed absolute baseline (API-36 device); `dH` = the signed
     * legacy→seam height delta (0 for non-CJK). Width delta is always 0 (asserted).
     */
    private data class Baseline(
        val label: String, val text: String, val typeface: Int, val size: Float,
        val seamW: Int, val seamH: Int, val dH: Int,
    )

    private data class Measured(val b: Baseline, val lw: Int, val lh: Int, val sw: Int, val sh: Int)

    // Non-CJK anchors — signed baseline == legacy (delta 0). Must stay exact across sizes.
    private val nonCjk = listOf(
        Baseline("GOLDEN@24", "GOLDEN", TextTypeface.Normal.serializeKey(), 24f, 93, 33, 0),
        Baseline("MULTILINE@24", "DO NOT\nREDISTRIBUTE", TextTypeface.Normal.serializeKey(), 24f, 161, 61, 0),
        Baseline("EMOJI@24", "👋 DO NOT REDISTRIBUTE", TextTypeface.Normal.serializeKey(), 24f, 288, 33, 0),
        Baseline("GOLDEN_BOLD@24", "GOLDEN", TextTypeface.Bold.serializeKey(), 24f, 93, 33, 0),
        Baseline("GOLDEN_ITALIC@24", "GOLDEN", TextTypeface.Italic.serializeKey(), 24f, 90, 33, 0),
        Baseline("GOLDEN@12", "GOLDEN", TextTypeface.Normal.serializeKey(), 12f, 46, 17, 0),
        Baseline("GOLDEN@48", "GOLDEN", TextTypeface.Normal.serializeKey(), 48f, 185, 65, 0),
    )

    // CJK — signed baseline accepts the Compose line-height growth (height only; width exact). Pinned to
    // SM-S906E / API 36. Delta scales with text size AND line count (single-line +1/+2/+5; 2-line +4/+9/+18).
    private val cjk = listOf(
        Baseline("CJK_MIX@12", "你好世界 watermark", TextTypeface.Normal.serializeKey(), 12f, 105, 18, 1),
        Baseline("CJK_SHORT@12", "水印", TextTypeface.Normal.serializeKey(), 12f, 22, 18, 1),
        Baseline("CJK_MULTILINE@12", "请勿转载\n仅供预览", TextTypeface.Normal.serializeKey(), 12f, 44, 35, 4),
        Baseline("CJK_MIX@24", "你好世界 watermark", TextTypeface.Normal.serializeKey(), 24f, 212, 35, 2),
        Baseline("CJK_SHORT@24", "水印", TextTypeface.Normal.serializeKey(), 24f, 46, 35, 2),
        Baseline("CJK_MULTILINE@24", "请勿转载\n仅供预览", TextTypeface.Normal.serializeKey(), 24f, 92, 70, 9),
        Baseline("CJK_MIX@48", "你好世界 watermark", TextTypeface.Normal.serializeKey(), 48f, 423, 70, 5),
        Baseline("CJK_SHORT@48", "水印", TextTypeface.Normal.serializeKey(), 48f, 92, 70, 5),
        Baseline("CJK_MULTILINE@48", "请勿转载\n仅供预览", TextTypeface.Normal.serializeKey(), 48f, 184, 140, 18),
    )

    private fun paintFor(b: Baseline): TextPaint {
        val config = WaterMark.default.copy(
            text = b.text, textSize = b.size,
            textTypeface = TextTypeface.obtainSealedClass(b.typeface), iconUri = Uri.EMPTY,
        )
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        return TextPaint().applyConfig(imageInfo, config, isScale = false)
    }

    /** Byte-identical to buildTextBitmapShader's measurement. */
    private fun legacyMeasure(paint: TextPaint, text: String): Pair<Int, Int> {
        var maxLineWidth = 0
        text.split("\n").forEach {
            val s = text.indexOf(it).coerceAtLeast(0)
            maxLineWidth = max(maxLineWidth, paint.measureText(text, s, (s + it.length).coerceAtMost(text.length)).toInt())
        }
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxLineWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return sl.width to sl.height
    }

    /** Candidate seam — delegates to the extracted [WatermarkTextMeasurer]/[TextMeasureEnv]. */
    private fun seamMeasure(paint: TextPaint, text: String): Pair<Int, Int> {
        val env = androidTextMeasureEnv(InstrumentationRegistry.getInstrumentation().targetContext)
        val size = WatermarkTextMeasurer.measure(env, text, paint.toWatermarkTextStyle())
        return size.width to size.height
    }

    private fun measureAndLog(b: Baseline): Measured {
        val paint = paintFor(b)
        val (lw, lh) = legacyMeasure(paint, b.text)
        val (sw, sh) = seamMeasure(paint, b.text)
        Log.i(tag, "PARITYGATE|${b.label}|legacy=${lw}x${lh}|seam=${sw}x${sh}|d=(${sw - lw},${sh - lh})|signedBaseline=${b.seamW}x${b.seamH}|signedDH=${b.dH}")
        return Measured(b, lw, lh, sw, sh)
    }

    private fun assertRow(m: Measured) {
        val b = m.b
        // (1) width parity is ALWAYS exact — width never drifts (device-independent).
        assertEquals("width parity [${b.label}]", m.lw, m.sw)
        // (2) signed legacy→seam height delta is exactly the recorded value (device-independent).
        assertEquals("signed height delta [${b.label}]", b.dH, m.sh - m.lh)
        // (3) signed absolute baseline (API-36 device) — no tolerance (device-pinned).
        assertEquals("signed baseline width [${b.label}]", b.seamW, m.sw)
        assertEquals("signed baseline height [${b.label}]", b.seamH, m.sh)
    }

    /** Non-CJK: seam == legacy exactly (signed delta 0) across sizes. */
    @Test
    fun nonCjk_cell_dimensions_match_legacy_exactly() {
        val rows = nonCjk.map { measureAndLog(it) } // log ALL rows before asserting
        rows.forEach { assertRow(it) }
    }

    /**
     * CJK: width exact; height equals the explicitly signed baseline/delta (candidate — Compose
     * line-height accepted as the C2b future-renderer baseline, pending sign-off). Green, not hidden.
     */
    @Test
    fun cjk_cell_dimensions_match_signed_baseline() {
        val rows = cjk.map { measureAndLog(it) } // log ALL rows before asserting
        rows.forEach { assertRow(it) }
    }
}
