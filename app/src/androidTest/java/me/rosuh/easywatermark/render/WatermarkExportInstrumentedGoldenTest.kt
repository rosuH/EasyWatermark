package me.rosuh.easywatermark.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView
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
 * Runs the SAME first corpus + the SAME faithful copy of the `MainViewModel.generateImage`
 * composition tail (REPEAT tile / CLAMP decal) on a real device, where emoji + CJK + rotation
 * rasterize with the device's actual fonts/Skia — the values Robolectric NATIVE cannot be trusted
 * to reproduce (ADR-0010 two-tier golden; CJK metrics are device-pinned).
 *
 * Authority: `RFCT414QBMZ` / SM-S906E / API 36. `emulator-5554` is SUPPLEMENTARY smoke only — its
 * absolute pixel counts are NOT a CJK oracle. This tier asserts only device-independent STRUCTURE
 * (nonblank / decal-corner-background / encode roundtrip); it logs absolute signatures for a human
 * to pin against the authority device when one is attached.
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
                WaterMarkImageView.buildTextBitmapShader(imageInfo, config, paint, Dispatchers.Unconfined)
            } else {
                val src = Bitmap.createBitmap(spec.iconW, spec.iconH, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    Canvas(this).drawRect(0f, 0f, spec.iconW / 2f, spec.iconH.toFloat(),
                        Paint().apply { color = Color.RED })
                }
                WaterMarkImageView.buildIconBitmapShader(
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

    /** Faithful copy of the `MainViewModel.generateImage` composition tail (see JVM twin). */
    private fun composite(
        imageW: Int, imageH: Int, shader: WaterMarkShader,
        tileMode: Shader.TileMode, offsetX: Float, offsetY: Float, bg: Int,
    ): IntArray {
        val bmp = Bitmap.createBitmap(imageW, imageH, Bitmap.Config.ARGB_8888).apply { eraseColor(bg) }
        val canvas = Canvas(bmp)
        val layoutPaint = Paint().apply { this.shader = shader.bitmapShader }
        if (tileMode == Shader.TileMode.CLAMP) {
            canvas.translate(offsetX * imageW, offsetY * imageH)
            canvas.drawRect(0f, 0f, shader.width.toFloat(), shader.height.toFloat(), layoutPaint)
        } else {
            canvas.drawRect(0f, 0f, imageW.toFloat(), imageH.toFloat(), layoutPaint)
        }
        return IntArray(imageW * imageH).also { bmp.getPixels(it, 0, imageW, 0, 0, imageW, imageH) }
    }

    private fun fnv1a(px: IntArray): Int {
        var h = -0x7ee3623b
        for (p in px) { h = h xor p; h *= 0x01000193 }
        return h
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
