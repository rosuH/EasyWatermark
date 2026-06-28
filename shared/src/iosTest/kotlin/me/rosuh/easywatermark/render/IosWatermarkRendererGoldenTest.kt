package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-192: the **iOS renderer perceptual/stability gate** (runs on `iosSimulatorArm64Test`) — the iOS
 * sibling of [DesktopTextRendererGoldenTest]. It proves the iOS Skiko render path
 * ([IosWatermarkRenderer.renderTextCell] / [IosWatermarkRenderer.renderIconCell] over the shared
 * [WatermarkCellComposer]) renders **non-blank and stably** for Latin, CJK, multiline, and rotated-315°
 * text plus the rotated icon cell, catching gross regressions (blank/collapsed/nondeterministic output)
 * with a coarse **perceptual signature** instead of fragile exact host-font pixels.
 *
 * The signature is a coarse 8×8 grid of **quantized ink levels** (per bucket: 0 = <5%, 1 = <20%,
 * 2 = <40%, 3 = ≥40% of its pixels non-transparent), robust to sub-pixel AA yet catching blank cells and
 * collapsed layout. The gate asserts: positive dims, visible ink, ≥1 inked bucket, and **determinism**
 * (two independent renders → the identical signature).
 *
 * **Font honesty (deliberately font-robust):** like the existing [IosWatermarkRendererTest], the text path
 * here uses [androidx.compose.ui.text.font.FontFamily.Default] (the iOS system font) — it does NOT depend
 * on packaging the bundled Latin/CJK font into an iOS bundle (that is C5; the bundled-font boundary is
 * compile-proven separately). So this gate does **not** assert CJK-vs-Latin signature difference or an
 * absolute CJK ink density (both are font-dependent); it proves iOS renderer **stability on the runtime**,
 * not app-bundle font packaging. CJK input is gated only for non-blank + determinism (the system font is
 * expected to provide glyphs/fallback; a degenerate blank/crash would fail).
 *
 * NOT an Android path: Android production text/icon stays native (S4d-8/S4d-17). This gates the iOS
 * renderer only.
 */
class IosWatermarkRendererGoldenTest {

    private val gridN = 8

    /** Per-bucket quantized ink level over an 8×8 grid: 0 = <5%, 1 = <20%, 2 = <40%, 3 = ≥40% inked. */
    private fun signature(bmp: ImageBitmap): List<Int> {
        val px = bmp.toPixelMap()
        val sig = ArrayList<Int>(gridN * gridN)
        val cellW = (px.width + gridN - 1) / gridN
        val cellH = (px.height + gridN - 1) / gridN
        for (gy in 0 until gridN) {
            for (gx in 0 until gridN) {
                var inked = 0; var total = 0
                val x0 = gx * cellW; val y0 = gy * cellH
                var y = y0
                while (y < minOf(y0 + cellH, px.height)) {
                    var x = x0
                    while (x < minOf(x0 + cellW, px.width)) {
                        total++
                        if (px[x, y].alpha > 0f) inked++
                        x++
                    }
                    y++
                }
                val frac = if (total > 0) inked.toDouble() / total else 0.0
                sig.add(if (frac >= 0.40) 3 else if (frac >= 0.20) 2 else if (frac >= 0.05) 1 else 0)
            }
        }
        return sig
    }

    private fun nonBlank(bmp: ImageBitmap): Int {
        val px = bmp.toPixelMap()
        var n = 0
        for (y in 0 until px.height) for (x in 0 until px.width) if (px[x, y].alpha > 0f) n++
        return n
    }

    private fun assertRendersNonblankAndStable(label: String, render: () -> ImageBitmap): List<Int> {
        val a = render()
        assertTrue(a.width > 0 && a.height > 0, "$label: cell must have positive dims")
        assertTrue(nonBlank(a) > 0, "$label: cell must render visible (non-transparent) pixels")
        val sigA = signature(a)
        assertTrue(sigA.any { it > 0 }, "$label: ink-occupancy signature must have at least one inked bucket")
        // Determinism / stability: a second independent render yields the identical perceptual signature.
        val sigB = signature(render())
        assertEquals(sigA, sigB, "$label: iOS renderer must be deterministic (stable signature)")
        return sigA
    }

    /** A small deterministic, opaque, non-uniform icon (font-independent) for the icon-cell gate. */
    private fun makeIcon(): ImageBitmap {
        val s = 24
        val bmp = ImageBitmap(s, s, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(s.toFloat(), s.toFloat())) {
            drawRect(color = Color(0xFFEE22AA))
            drawRect(color = Color(0xFFFFFFFF), topLeft = Offset(6f, 6f), size = Size(8f, 8f))
        }
        return bmp
    }

    @Test
    fun latin_text_cell_nonblank_and_stable() {
        assertRendersNonblankAndStable("latin_0") {
            IosWatermarkRenderer.renderTextCell(text = "GOLDEN", degree = 0f)
        }
    }

    @Test
    fun cjk_text_cell_nonblank_and_stable() {
        // System-font path: assert only non-blank + deterministic (no CJK-vs-Latin difference / density claim).
        assertRendersNonblankAndStable("cjk_0") {
            IosWatermarkRenderer.renderTextCell(text = "请勿转载", degree = 0f)
        }
    }

    @Test
    fun multiline_text_cell_inks_top_and_bottom_bands() {
        val sig = assertRendersNonblankAndStable("multiline_0") {
            IosWatermarkRenderer.renderTextCell(text = "DO NOT\nREDISTRIBUTE", degree = 0f)
        }
        val topInked = (0 until gridN * (gridN / 3)).any { sig[it] > 0 }
        val bottomInked = (gridN * (gridN - gridN / 3) until gridN * gridN).any { sig[it] > 0 }
        assertTrue(topInked && bottomInked, "multiline must render ink in both top and bottom bands")
    }

    @Test
    fun rotated_text_cell_nonblank_and_stable() {
        assertRendersNonblankAndStable("ascii_315") {
            IosWatermarkRenderer.renderTextCell(text = "GOLDEN", degree = 315f)
        }
    }

    @Test
    fun rotated_icon_cell_nonblank_and_stable() {
        // Font-independent: a rotated 2× non-uniform icon cell must raster visibly and deterministically.
        assertRendersNonblankAndStable("icon_315") {
            IosWatermarkRenderer.renderIconCell(icon = makeIcon(), degree = 315f, scaleRatio = 2f)
        }
    }
}
