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
 * C2b **accepted signed-baseline** measurement gate (S3b / D1). The `TextMeasurer` measurement seam
 * [WatermarkTextMeasurer] + [TextMeasureEnv] is now wired into the product renderer
 * ([me.rosuh.easywatermark.render.WatermarkRenderer.buildTextShader]) for text-cell measurement
 * (drawing stays legacy `StaticLayout`); this gate pins the same seam the product uses. Signed-baseline
 * behavior: non-CJK strict legacy==seam (width AND height); CJK exact width + exact signed delta +
 * exact signed absolute baseline (no tolerance widening; every row logged `PARITYGATE|…`).
 *
 * ACCEPTED (S3b) — the CJK baselines encode "Option 1" (Compose CJK line-height accepted as the
 * renderer baseline), promoted from candidate per the accepted D1 sign-off (ADR-0014/ADR-0004).
 *
 * PLATFORM-PINNED (ADR-0010): the CJK absolute baselines below are a same-platform device baseline. Under
 * the updated device policy (2026-06-14: any available adb target is acceptable, do not require a
 * specific serial) they were re-pinned to the **acceptance target used for S3b verification:
 * emulator-5554 / AVD Pixel_9_Pro_XL / Android 16 / API 36** (model `sdk_gphone64_arm64`). Robolectric
 * is not a CJK dimension oracle; this gate is instrumented/device-only. Non-CJK rows assert parity and
 * delta only, because their absolute glyph advances are device-font dependent under the any-device
 * policy. Re-baseline the CJK absolute rows on the pinned CI device if the fleet changes.
 *
 * Device note: CJK *heights* matched the earlier SM-S906E baseline exactly (18/35/70/140); only the
 * CJK glyph *widths* differed by a few px (wider Noto font) — recorded here, not treated as a blocker
 * (updated policy).
 */
@RunWith(AndroidJUnit4::class)
class WatermarkCellParityGateTest {

    private val tag = "PARITYGATE"

    /**
     * One gate row. `seamW`/`seamH` = the CJK signed absolute baseline (API-36 device); `dH` = the
     * signed legacy→seam height delta (0 for non-CJK). Width delta is always 0 (asserted).
     */
    private data class Baseline(
        val label: String, val text: String, val typeface: Int, val size: Float,
        val dH: Int, val seamW: Int? = null, val seamH: Int? = null,
    )

    private data class Measured(val b: Baseline, val lw: Int, val lh: Int, val sw: Int, val sh: Int)

    // Non-CJK anchors — signed baseline == legacy (delta 0). The product contract is parity, not
    // device-pinned absolute glyph advances, under the any-device policy.
    private val nonCjk = listOf(
        Baseline("GOLDEN@24", "GOLDEN", TextTypeface.Normal.serializeKey(), 24f, dH = 0),
        Baseline("MULTILINE@24", "DO NOT\nREDISTRIBUTE", TextTypeface.Normal.serializeKey(), 24f, dH = 0),
        Baseline("EMOJI@24", "👋 DO NOT REDISTRIBUTE", TextTypeface.Normal.serializeKey(), 24f, dH = 0),
        Baseline("GOLDEN_BOLD@24", "GOLDEN", TextTypeface.Bold.serializeKey(), 24f, dH = 0),
        Baseline("GOLDEN_ITALIC@24", "GOLDEN", TextTypeface.Italic.serializeKey(), 24f, dH = 0),
        Baseline("GOLDEN@12", "GOLDEN", TextTypeface.Normal.serializeKey(), 12f, dH = 0),
        Baseline("GOLDEN@48", "GOLDEN", TextTypeface.Normal.serializeKey(), 48f, dH = 0),
    )

    // CJK — signed baseline accepts the Compose line-height growth (height only; width exact vs legacy).
    // Absolutes re-pinned to emulator-5554 / API 36. CJK HEIGHTS match the earlier SM-S906E baseline
    // exactly (18/35/70/140); only CJK glyph WIDTHS are a few px wider (Noto). Signed ΔH unchanged
    // (device-independent: single-line +1/+2/+5; 2-line +4/+9/+18).
    private val cjk = listOf(
        Baseline("CJK_MIX@12", "你好世界 watermark", TextTypeface.Normal.serializeKey(), 12f, dH = 1, seamW = 109, seamH = 18),
        Baseline("CJK_SHORT@12", "水印", TextTypeface.Normal.serializeKey(), 12f, dH = 1, seamW = 24, seamH = 18),
        Baseline("CJK_MULTILINE@12", "请勿转载\n仅供预览", TextTypeface.Normal.serializeKey(), 12f, dH = 4, seamW = 48, seamH = 35),
        Baseline("CJK_MIX@24", "你好世界 watermark", TextTypeface.Normal.serializeKey(), 24f, dH = 2, seamW = 216, seamH = 35),
        Baseline("CJK_SHORT@24", "水印", TextTypeface.Normal.serializeKey(), 24f, dH = 2, seamW = 48, seamH = 35),
        Baseline("CJK_MULTILINE@24", "请勿转载\n仅供预览", TextTypeface.Normal.serializeKey(), 24f, dH = 9, seamW = 96, seamH = 70),
        Baseline("CJK_MIX@48", "你好世界 watermark", TextTypeface.Normal.serializeKey(), 48f, dH = 5, seamW = 431, seamH = 70),
        Baseline("CJK_SHORT@48", "水印", TextTypeface.Normal.serializeKey(), 48f, dH = 5, seamW = 96, seamH = 70),
        Baseline(
            "CJK_MULTILINE@48", "请勿转载\n仅供预览", TextTypeface.Normal.serializeKey(),
            48f, dH = 18, seamW = 192, seamH = 140,
        ),
    )

    private fun paintFor(b: Baseline): TextPaint {
        val config = WaterMark.default.copy(
            text = b.text, textSize = b.size,
            textTypeface = TextTypeface.obtainSealedClass(b.typeface), iconUri = Uri.EMPTY,
        )
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        return TextPaint().applyConfig(imageInfo, config, isScale = false)
    }

    /** Legacy StaticLayout measurement used as the parity reference for product output. */
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

    /** Product seam — delegates to the extracted [WatermarkTextMeasurer]/[TextMeasureEnv]. */
    private fun seamMeasure(paint: TextPaint, text: String): Pair<Int, Int> {
        val env = androidTextMeasureEnv(InstrumentationRegistry.getInstrumentation().targetContext)
        val size = WatermarkTextMeasurer.measure(env, text, paint.toWatermarkTextStyle())
        return size.width to size.height
    }

    private fun measureAndLog(b: Baseline): Measured {
        val paint = paintFor(b)
        val (lw, lh) = legacyMeasure(paint, b.text)
        val (sw, sh) = seamMeasure(paint, b.text)
        val signedBaseline = if (b.seamW != null && b.seamH != null) "${b.seamW}x${b.seamH}" else "n/a"
        Log.i(
            tag,
            "PARITYGATE|${b.label}|legacy=${lw}x${lh}|seam=${sw}x${sh}|d=(${sw - lw},${sh - lh})|" +
                "signedBaseline=$signedBaseline|signedDH=${b.dH}",
        )
        return Measured(b, lw, lh, sw, sh)
    }

    private fun assertRow(m: Measured, assertAbsoluteBaseline: Boolean) {
        val b = m.b
        // (1) width parity is ALWAYS exact — width never drifts (device-independent).
        assertEquals("width parity [${b.label}]", m.lw, m.sw)
        // (2) signed legacy→seam height delta is exactly the recorded value (device-independent).
        assertEquals("signed height delta [${b.label}]", b.dH, m.sh - m.lh)
        if (assertAbsoluteBaseline) {
            val seamW = requireNotNull(b.seamW) { "missing signed baseline width [${b.label}]" }
            val seamH = requireNotNull(b.seamH) { "missing signed baseline height [${b.label}]" }
            // (3) signed absolute baseline (API-36 device) — no tolerance (device-pinned).
            assertEquals("signed baseline width [${b.label}]", seamW, m.sw)
            assertEquals("signed baseline height [${b.label}]", seamH, m.sh)
        }
    }

    /** Non-CJK: seam == legacy exactly (signed delta 0) across sizes. */
    @Test
    fun nonCjk_cell_dimensions_match_legacy_exactly() {
        val rows = nonCjk.map { measureAndLog(it) } // log ALL rows before asserting
        rows.forEach { assertRow(it, assertAbsoluteBaseline = false) }
    }

    /**
     * CJK: width exact; height equals the explicitly signed baseline/delta. Compose line-height is the
     * accepted S3b/D1 renderer baseline. Green, not hidden.
     */
    @Test
    fun cjk_cell_dimensions_match_signed_baseline() {
        val rows = cjk.map { measureAndLog(it) } // log ALL rows before asserting
        rows.forEach { assertRow(it, assertAbsoluteBaseline = true) }
    }
}
