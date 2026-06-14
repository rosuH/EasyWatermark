package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * S0 / CMP plan C1.7 — EXPORT golden harness for the CURRENT watermark engine.
 *
 * Where [WatermarkCellGoldenTest] pins only single-cell dimensions/nonblank, this harness freezes
 * the **export composition** — the REPEAT-tile / CLAMP-decal math that
 * [me.rosuh.easywatermark.ui.MainViewModel.generateImage] applies to the saved image — over a first
 * corpus (multiline, emoji@315, CJK, icon, gap extremes) and exercises the JPEG/PNG encode path.
 *
 * It is the safety net that must be GREEN and trusted before any `WatermarkRenderer` extraction.
 * It captures current behavior; it does not improve it.
 *
 * Oracle: the production cell builders [WaterMarkImageView.buildTextBitmapShader] /
 * [WaterMarkImageView.buildIconBitmapShader] (called directly — they are `companion` + public),
 * plus a test-local FAITHFUL COPY of the `generateImage` composition tail (see [composite]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // plain app — avoids MyApp.startKoin double-start across the suite
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkExportGoldenTest {

    // ---- corpus spec -------------------------------------------------------------------------

    private data class CellSpec(
        val label: String,
        val text: String? = null,           // text cell
        val iconW: Int = 0,                 // icon cell (when text == null)
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
        CellSpec("cjk", text = "水印测试", degree = 0f),            // 水印测试
        CellSpec("cjk_multiline_315", text = "中文\n水印", degree = 315f), // 中文 / 水印
        CellSpec("gap_h_extreme", text = "GOLDEN", degree = 0f, hGap = 300, vGap = 0),
        CellSpec("gap_v_extreme", text = "GOLDEN", degree = 0f, hGap = 0, vGap = 300),
        CellSpec("icon_40x20", iconW = 40, iconH = 20, degree = 0f, textSize = 14f),
        CellSpec("icon_rot_315", iconW = 40, iconH = 20, degree = 315f, textSize = 14f),
    )

    // ---- engine oracle wiring ----------------------------------------------------------------

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
                    // a non-uniform mark so rotation/scale changes are observable
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

    /** Renders ONE cell onto a transparent bitmap of the cell's own size (matches existing goldens). */
    private fun renderCell(shader: WaterMarkShader): IntArray {
        val w = shader.width.coerceAtLeast(1)
        val h = shader.height.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader.bitmapShader })
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    /**
     * FAITHFUL COPY of the export composition tail in `MainViewModel.generateImage`
     * (current `master` lines ~373-392): paint the cell's [BitmapShader] over an opaque image —
     * REPEAT fills the whole image; CLAMP translates by the fractional offset and paints one
     * cell-sized decal. The product method itself is `private` and needs a `ContentResolver` +
     * MediaStore, so it cannot be invoked directly from a unit test; this mirror exercises the same
     * canvas math at export scale 1:1. (Documented untested seams: the `1/MSCALE_X` preview-scale
     * derivation and the MediaStore encode-to-disk — see harness-design.md.)
     */
    private fun composite(
        imageW: Int,
        imageH: Int,
        shader: WaterMarkShader,
        tileMode: Shader.TileMode,
        offsetX: Float,
        offsetY: Float,
        bg: Int,
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
        var h = -0x7ee3623b // 2166136261
        for (p in px) {
            h = h xor p
            h *= 0x01000193
        }
        return h
    }

    /**
     * Recorded Robolectric-NATIVE @sdk34 baseline signatures (captured 2026-06-14, see
     * artifacts/baseline-manifest.md). These are a REGRESSION NET, not a device-pixel reference:
     * absolute pixels/dims are Robolectric-environment values (CJK especially is NOT the device
     * oracle — RFCT414QBMZ is, via the instrumented tier). The default gate asserts only
     * device-independent structure (nonblank/geometry/periodicity/decal/encode). Run with
     * `WATERMARK_GOLDEN_STRICT=true ./gradlew :app:testDebugUnitTest` to additionally pin every
     * signature below, intended for a stable pinned environment and re-captured on an intentional
     * Robolectric/Skia/font bump. (Gradle forwards its environment to the forked test JVM; a plain
     * `-D` system property does NOT propagate, so an env var is used; `-D` is an IDE-run fallback.)
     */
    private data class Sig(
        val cellW: Int, val cellH: Int, val cellNonBlank: Int,
        val cellFnv: Int, val repeatFnv: Int, val clampFnv: Int,
    )

    private val baselines: Map<String, Sig> = mapOf(
        "ascii_0" to Sig(93, 33, 845, -1154811034, -1856277548, 1859426124),
        "multiline" to Sig(110, 61, 1482, 1966207549, 976645029, -1767901595),
        "emoji_default_315" to Sig(228, 228, 2755, 506778156, 445027089, -303496632),
        "cjk" to Sig(96, 33, 983, 1574907974, -2106528931, 1819751940),
        "cjk_multiline_315" to Sig(77, 77, 877, -2051947968, 70436952, 425106212),
        "gap_h_extreme" to Sig(372, 33, 845, 367245244, -1898366714, 1853155435),
        "gap_v_extreme" to Sig(93, 132, 845, -651002264, -1009720531, -6622388),
        "icon_40x20" to Sig(44, 44, 800, 2099708661, -480694123, 1766162126),
        "icon_rot_315" to Sig(44, 44, 912, 1454838001, -261395011, -1638977274),
    )

    private val strict: Boolean =
        System.getenv("WATERMARK_GOLDEN_STRICT") == "true" ||
            System.getProperty("watermark.golden.strict") == "true"

    /**
     * Whole-corpus export signature gate. DEFAULT: asserts every cell + its REPEAT tiling + its
     * CLAMP decal render NON-BLANK (the blank-render regression net — the exact failure class the
     * C2a engine-wiring attempt produced). STRICT (`WATERMARK_GOLDEN_STRICT=true`): additionally
     * pins cell dims + nonblank + composite FNV signatures to [baselines]. Prints the full manifest
     * either way so a reviewer can diff captured signatures.
     */
    @Test
    fun export_corpus_signature_manifest() {
        println("=== WATERMARK-EXPORT-MANIFEST (Robolectric NATIVE @ sdk34, strict=$strict) ===")
        for (spec in corpus) {
            val shader = buildShader(spec)
            val cellPx = renderCell(shader)
            val cellNonBlank = cellPx.count { it != 0 }
            val cellFnv = fnv1a(cellPx)

            val repeatPx = composite(256, 256, shader, Shader.TileMode.REPEAT, 0f, 0f, Color.DKGRAY)
            val repeatNonBg = repeatPx.count { it != Color.DKGRAY }
            val repeatFnv = fnv1a(repeatPx)

            val clampPx = composite(256, 256, shader, Shader.TileMode.CLAMP, 0.4f, 0.4f, Color.DKGRAY)
            val clampNonBg = clampPx.count { it != Color.DKGRAY }
            val clampFnv = fnv1a(clampPx)

            println(
                "EXPORT ${spec.label} cell=${shader.width}x${shader.height} " +
                    "cellNonBlank=$cellNonBlank cellFnv=$cellFnv " +
                    "repeatNonBg=$repeatNonBg repeatFnv=$repeatFnv " +
                    "clampNonBg=$clampNonBg clampFnv=$clampFnv"
            )

            // Default device-independent gate: nothing in the corpus may render blank.
            assertTrue("'${spec.label}' cell has positive dims", shader.width > 0 && shader.height > 0)
            assertTrue("'${spec.label}' cell must render visible pixels", cellNonBlank > 0)
            assertTrue("'${spec.label}' REPEAT tiling must paint over background", repeatNonBg > 0)
            assertTrue("'${spec.label}' CLAMP decal must paint over background", clampNonBg > 0)

            if (strict) {
                val b = baselines.getValue(spec.label)
                assertEquals("[strict] '${spec.label}' cellW", b.cellW, shader.width)
                assertEquals("[strict] '${spec.label}' cellH", b.cellH, shader.height)
                assertEquals("[strict] '${spec.label}' cellNonBlank", b.cellNonBlank, cellNonBlank)
                assertEquals("[strict] '${spec.label}' cellFnv", b.cellFnv, cellFnv)
                assertEquals("[strict] '${spec.label}' repeatFnv", b.repeatFnv, repeatFnv)
                assertEquals("[strict] '${spec.label}' clampFnv", b.clampFnv, clampFnv)
            }
        }
    }

    // ---- gap-geometry gate (device-independent math) -----------------------------------------

    @Test
    fun gap_extremes_scale_only_their_axis() {
        val base = buildShader(CellSpec("base", text = "GOLDEN", degree = 0f))
        val hExt = buildShader(CellSpec("hExt", text = "GOLDEN", degree = 0f, hGap = 300, vGap = 0))
        val vExt = buildShader(CellSpec("vExt", text = "GOLDEN", degree = 0f, hGap = 0, vGap = 300))
        println("GAP base=${base.width}x${base.height} hExt=${hExt.width}x${hExt.height} vExt=${vExt.width}x${vExt.height}")
        // horizontalGap(maxSize, gap) = maxSize * (gap/100 + 1); gap=300 -> 4x on that axis only.
        assertEquals("hGap=300 -> width 4x", base.width * 4, hExt.width)
        assertEquals("hGap leaves height untouched", base.height, hExt.height)
        assertEquals("vGap=300 -> height 4x", base.height * 4, vExt.height)
        assertEquals("vGap leaves width untouched", base.width, vExt.width)
    }

    // ---- REPEAT periodicity gate -------------------------------------------------------------

    @Test
    fun repeat_tiles_with_cell_period() {
        val shader = buildShader(CellSpec("ascii", text = "GOLDEN", degree = 0f))
        val cw = shader.width
        val ch = shader.height
        val w = 256
        val h = 256
        val px = composite(w, h, shader, Shader.TileMode.REPEAT, 0f, 0f, Color.DKGRAY)
        // The same cell repeats from canvas origin -> pixel(x,y) == pixel(x+cw, y) == pixel(x, y+ch).
        var checked = 0
        var x = 0
        while (x + cw < w) {
            var y = 0
            while (y < h) {
                assertEquals(
                    "REPEAT horizontal period mismatch at ($x,$y)",
                    px[y * w + x], px[y * w + (x + cw)],
                )
                checked++
                y += 13
            }
            x += 7
        }
        assertTrue("periodicity actually sampled", checked > 0)
    }

    // ---- CLAMP decal gate --------------------------------------------------------------------

    @Test
    fun clamp_paints_single_decal_over_background() {
        val shader = buildShader(CellSpec("ascii", text = "GOLDEN", degree = 0f))
        val w = 256
        val h = 256
        val offset = 0.4f
        val px = composite(w, h, shader, Shader.TileMode.CLAMP, offset, offset, Color.DKGRAY)
        // Top-left corner is outside a small decal placed at 0.4*W -> stays pure background.
        assertEquals("CLAMP must not tile: corner stays background", Color.DKGRAY, px[0])
        // The decal region must contain non-background pixels.
        val nonBg = px.count { it != Color.DKGRAY }
        assertTrue("CLAMP decal must paint visible pixels", nonBg > 0)
        // And far fewer than a full REPEAT tiling (one instance, not the whole image).
        val repeatNonBg = composite(w, h, shader, Shader.TileMode.REPEAT, 0f, 0f, Color.DKGRAY)
            .count { it != Color.DKGRAY }
        assertTrue("CLAMP paints fewer pixels than REPEAT", nonBg < repeatNonBg)
    }

    // ---- JPEG / PNG encode roundtrip gate ----------------------------------------------------

    @Test
    fun export_encodes_jpeg_and_png() {
        val shader = buildShader(CellSpec("ascii", text = "GOLDEN", degree = 0f))
        val w = 128
        val h = 128
        val px = composite(w, h, shader, Shader.TileMode.REPEAT, 0f, 0f, Color.DKGRAY)
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        src.setPixels(px, 0, w, 0, 0, w, h)

        for (fmt in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            val baos = ByteArrayOutputStream()
            val ok = src.compress(fmt, 90, baos)
            val bytes = baos.toByteArray()
            assertTrue("$fmt compress returned true", ok)
            assertTrue("$fmt produced bytes", bytes.isNotEmpty())
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("$fmt decodes back", decoded)
            assertEquals("$fmt preserves width", w, decoded.width)
            assertEquals("$fmt preserves height", h, decoded.height)
            println("ENCODE $fmt bytes=${bytes.size} decoded=${decoded.width}x${decoded.height}")
        }
    }
}
