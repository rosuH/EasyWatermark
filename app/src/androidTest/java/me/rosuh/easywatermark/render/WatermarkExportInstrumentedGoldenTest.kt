package me.rosuh.easywatermark.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.ui.widget.utils.WaterMarkShader
import me.rosuh.easywatermark.utils.ktx.applyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * S0 / CMP plan C1.7 — DEVICE-AUTHORITY mirror of [WatermarkExportGoldenTest].
 *
 * Runs the SAME first corpus through the SAME real [WatermarkRenderer.compose] seam (REPEAT tile /
 * CLAMP decal) on a real device, where emoji + CJK + rotation rasterize with the device's actual
 * fonts/Skia — the values Robolectric NATIVE cannot be trusted to reproduce (ADR-0010 two-tier
 * golden; CJK metrics are device-pinned).
 *
 * Two assertion tiers:
 *  - [export_corpus_renders_nonblank_on_device] / [clamp_decal_corner_is_background_on_device] /
 *    [export_encodes_jpeg_and_png_on_device] assert device-INDEPENDENT structure and run on ANY device.
 *  - [export_corpus_matches_device_pinned_baseline] (S4b) asserts the exact cell dims + cell/CLAMP
 *    FNV, but ONLY when the running device fingerprint (`MODEL/SDK`) matches a captured entry in
 *    [baselinesByDevice]; on any other device it logs the captured signature for a human to pin
 *    (so it never spuriously fails under the any-available-device policy, ADR-0014 device note).
 *
 * Pinned device(s): `sdk_gphone64_arm64/36` — emulator-5554 / Pixel_9_Pro_XL (AVD) / Android 16 /
 * API 36, the S3b acceptance target, captured 2026-06-16 through the real `compose` seam. The
 * SM-S906E (`RFCT414QBMZ`) authority pin is TBD when that device is attached. Re-baseline / add a
 * fingerprint entry when the fleet changes; do NOT widen tolerance.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkExportInstrumentedGoldenTest {

    private data class CellSpec(
        val label: String,
        val text: String? = null,
        val iconW: Int = 0,
        val iconH: Int = 0,
        val degree: Float = 0f,
        val hGap: Int = 0,
        val vGap: Int = 0,
        val textSize: Float = 24f,
    )

    private val corpus = listOf(
        CellSpec("ascii_0", text = "GOLDEN", degree = 0f),
        CellSpec("multiline", text = "LINE ONE\nLINE TWO", degree = 0f),
        CellSpec("emoji_default_315", text = "👋 DO NOT REDISTRIBUTE", degree = 315f),
        CellSpec("cjk", text = "水印测试", degree = 0f),
        CellSpec("cjk_multiline_315", text = "中文\n水印", degree = 315f),
        CellSpec("gap_h_extreme", text = "GOLDEN", degree = 0f, hGap = 300, vGap = 0),
        CellSpec("gap_v_extreme", text = "GOLDEN", degree = 0f, hGap = 0, vGap = 300),
        CellSpec("icon_40x20", iconW = 40, iconH = 20, degree = 0f, textSize = 14f),
        CellSpec("icon_rot_315", iconW = 40, iconH = 20, degree = 315f, textSize = 14f),
    )

    private fun buildShader(spec: CellSpec): WaterMarkShader {
        val imageInfo = ImageInfo.empty().apply { width = 1000; height = 1000 }
        val config = WaterMark.default.copy(
            text = spec.text ?: WaterMark.default.text,
            degree = spec.degree,
            hGap = spec.hGap,
            vGap = spec.vGap,
            textSize = spec.textSize,
            textColor = Color.WHITE,
            iconUri = Uri.EMPTY,
        )
        val shader = runBlocking {
            if (spec.text != null) {
                val paint = TextPaint().applyConfig(imageInfo, config, isScale = false)
                WatermarkRenderer.buildTextShader(
                    imageInfo, config, paint,
                    androidTextMeasureEnv(InstrumentationRegistry.getInstrumentation().targetContext),
                    Dispatchers.Unconfined,
                )
            } else {
                val src = Bitmap.createBitmap(spec.iconW, spec.iconH, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    Canvas(this).drawRect(0f, 0f, spec.iconW / 2f, spec.iconH.toFloat(),
                        Paint().apply { color = Color.RED })
                }
                WatermarkRenderer.buildIconShader(
                    imageInfo, src, config, Paint(), /* scale = */ false, Dispatchers.Unconfined,
                )
            }
        }
        assertNotNull("shader must build for '${spec.label}'", shader)
        return shader!!
    }

    private fun renderCell(shader: WaterMarkShader): IntArray {
        val w = shader.width.coerceAtLeast(1)
        val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /** S4b: composite through the REAL [WatermarkRenderer.compose] seam (see JVM twin). */
    private fun composite(
        imageW: Int, imageH: Int, shader: WaterMarkShader,
        tileMode: Shader.TileMode, offsetX: Float, offsetY: Float, bg: Int,
    ): IntArray {
        val bmp = Bitmap.createBitmap(imageW, imageH, Bitmap.Config.ARGB_8888).apply { eraseColor(bg) }
        WatermarkRenderer.compose(
            canvas = Canvas(bmp),
            shader = shader,
            tileMode = tileMode,
            paint = Paint(),
            left = 0f,
            top = 0f,
            regionWidth = imageW.toFloat(),
            regionHeight = imageH.toFloat(),
            offsetX = offsetX,
            offsetY = offsetY,
        )
        return IntArray(imageW * imageH).also { bmp.getPixels(it, 0, imageW, 0, 0, imageW, imageH) }
    }

    private fun fnv1a(px: IntArray): Int {
        var h = -0x7ee3623b
        for (p in px) { h = h xor p; h *= 0x01000193 }
        return h
    }

    /** Device-pinned export signature for one corpus cell: cell dims + cell FNV + CLAMP-decal FNV. */
    private data class Sig(val cellW: Int, val cellH: Int, val cellFnv: Int, val clampFnv: Int)

    /** `"$MODEL/$SDK"` of the device under test (the baseline fingerprint key). */
    private fun deviceKey(): String = "${Build.MODEL}/${Build.VERSION.SDK_INT}"

    /**
     * Device-pinned export baselines keyed by `MODEL/SDK`. Captured 2026-06-16 on
     * emulator-5554 / Pixel_9_Pro_XL (AVD) / `sdk_gphone64_arm64` / Android 16 / API 36 through the
     * real [WatermarkRenderer.compose] seam (`composite(256, 256, …)`, REPEAT cell + CLAMP @ 0.4/0.4).
     * Absolutes are device-specific (Skia/font), so they are asserted only when [deviceKey] matches.
     */
    private val baselinesByDevice: Map<String, Map<String, Sig>> = mapOf(
        "sdk_gphone64_arm64/36" to mapOf(
            "ascii_0" to Sig(93, 33, -366281882, 1458584107),
            "multiline" to Sig(110, 61, -1422790083, 765160644),
            "emoji_default_315" to Sig(228, 228, -1575206964, -978270002),
            "cjk" to Sig(96, 35, 180443926, -355393120),
            "cjk_multiline_315" to Sig(83, 83, 646986396, -528585465),
            "gap_h_extreme" to Sig(372, 33, 182695868, -965358109),
            "gap_v_extreme" to Sig(93, 132, 3309160, 440425003),
            "icon_40x20" to Sig(44, 44, 2099708661, 1766162126),
            "icon_rot_315" to Sig(44, 44, 1454838001, -1638977274),
        ),
    )

    /**
     * S4b: assert the captured device-pinned export signatures (cell dims + cell/CLAMP FNV) so a
     * renderer change that drifts pixels on the SAME device/API is caught. Guarded by [deviceKey]:
     * on an unpinned device it logs the captured signature instead of failing (any-available-device
     * policy). Structural nonblank/decal asserts live in [export_corpus_renders_nonblank_on_device]
     * and run everywhere.
     */
    @Test
    fun export_corpus_matches_device_pinned_baseline() {
        val key = deviceKey()
        val pinned = baselinesByDevice[key]
        Log.i("INSTR-EXPORT", "=== device-pinned export baselines for $key (pinned=${pinned != null}) ===")
        for (spec in corpus) {
            val shader = buildShader(spec)
            val cellFnv = fnv1a(renderCell(shader))
            val clampPx = composite(256, 256, shader, Shader.TileMode.CLAMP, 0.4f, 0.4f, Color.DKGRAY)
            val clampFnv = fnv1a(clampPx)
            val sig = Sig(shader.width, shader.height, cellFnv, clampFnv)
            Log.i("INSTR-EXPORT", "BASELINE ${spec.label} -> Sig(${sig.cellW}, ${sig.cellH}, ${sig.cellFnv}, ${sig.clampFnv})")
            val b = pinned?.get(spec.label)
            if (b == null) {
                Log.i("INSTR-EXPORT", "no pinned baseline for '${spec.label}' on $key — logged for human pin")
                continue
            }
            assertEquals("[pinned $key] '${spec.label}' cellW", b.cellW, sig.cellW)
            assertEquals("[pinned $key] '${spec.label}' cellH", b.cellH, sig.cellH)
            assertEquals("[pinned $key] '${spec.label}' cellFnv", b.cellFnv, sig.cellFnv)
            assertEquals("[pinned $key] '${spec.label}' clampFnv", b.clampFnv, sig.clampFnv)
        }
    }

    @Test
    fun export_corpus_renders_nonblank_on_device() {
        Log.i("INSTR-EXPORT", "=== device export manifest (authority: RFCT414QBMZ; emulator is smoke only) ===")
        for (spec in corpus) {
            val shader = buildShader(spec)
            val cellNonBlank = renderCell(shader).count { it != 0 }
            val repeatNonBg = composite(256, 256, shader, Shader.TileMode.REPEAT, 0f, 0f, Color.DKGRAY)
                .count { it != Color.DKGRAY }
            val clampPx = composite(256, 256, shader, Shader.TileMode.CLAMP, 0.4f, 0.4f, Color.DKGRAY)
            val clampNonBg = clampPx.count { it != Color.DKGRAY }
            Log.i(
                "INSTR-EXPORT",
                "${spec.label} cell=${shader.width}x${shader.height} cellNonBlank=$cellNonBlank " +
                    "repeatNonBg=$repeatNonBg clampNonBg=$clampNonBg " +
                    "cellFnv=${fnv1a(renderCell(shader))} clampFnv=${fnv1a(clampPx)}",
            )
            assertTrue("'${spec.label}' cell has positive dims", shader.width > 0 && shader.height > 0)
            assertTrue("'${spec.label}' cell renders visible pixels on device", cellNonBlank > 0)
            assertTrue("'${spec.label}' REPEAT tiling paints on device", repeatNonBg > 0)
            assertTrue("'${spec.label}' CLAMP decal paints on device", clampNonBg > 0)
        }
    }

    @Test
    fun clamp_decal_corner_is_background_on_device() {
        val shader = buildShader(CellSpec("ascii", text = "GOLDEN", degree = 0f))
        val px = composite(256, 256, shader, Shader.TileMode.CLAMP, 0.4f, 0.4f, Color.DKGRAY)
        assertEquals("CLAMP must not tile: corner stays background", Color.DKGRAY, px[0])
    }

    @Test
    fun export_encodes_jpeg_and_png_on_device() {
        val shader = buildShader(CellSpec("ascii", text = "GOLDEN", degree = 0f))
        val w = 128; val h = 128
        val px = composite(w, h, shader, Shader.TileMode.REPEAT, 0f, 0f, Color.DKGRAY)
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        src.setPixels(px, 0, w, 0, 0, w, h)
        for (fmt in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            val baos = ByteArrayOutputStream()
            assertTrue("$fmt compress", src.compress(fmt, 90, baos))
            val bytes = baos.toByteArray()
            assertTrue("$fmt bytes", bytes.isNotEmpty())
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("$fmt decodes", decoded)
            assertEquals("$fmt width", w, decoded.width)
            assertEquals("$fmt height", h, decoded.height)
        }
    }
}
