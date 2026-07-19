package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C0.1 shared paint contract matrix (issue 18): real [CommonWatermarkPipeline.compose]
 * on Desktop/Skiko with deterministic opaque background and changed-pixel geometry.
 */
class CommonWatermarkPipelineComposeTest {

    private val env = desktopTextRasterEnv()
    private val bgColor = Color(0xFF203040)
    private val imgW = 320
    private val imgH = 240
    private val rgbEps = 0.02f

    /** Shared P6/P7 icon fixture — one instance for both cases (issue 18 §4.2). */
    private val sharedAsymmetricIcon: ImageBitmap = asymmetricIcon()

    /** Shared P6/P7 base config; only tileMode/degree/alpha/offset vary per case. */
    private val sharedIconBaseConfig: WaterMark = WaterMark.default.copy(
        textSize = 14f,
        markMode = WatermarkMode.Image,
        hGap = 0,
        vGap = 0,
    )

    private data class PixelDeltaStats(
        val changedCount: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val centroidX: Double,
        val centroidY: Double,
        val changedCoords: List<Pair<Int, Int>>,
    ) {
        val bboxW: Int get() = maxX - minX + 1
        val bboxH: Int get() = maxY - minY + 1
    }

    // ─── P1 ───────────────────────────────────────────────────────────────

    @Test
    fun p1_text_en_fill_normal_repeat_alpha255_broad() {
        val bg = opaqueBg()
        val config = WaterMark.default.copy(
            text = "EasyWatermark",
            textSize = 32f,
            textStyle = TextPaintStyle.Fill,
            textTypeface = TextTypeface.Normal,
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            hGap = 0,
            vGap = 0,
            alpha = 255,
            markMode = WatermarkMode.Text,
        )
        val out = CommonWatermarkPipeline.compose(bg, config, env, null, 0.5f, 0.5f)
        assertEquals(imgW, out.width)
        assertEquals(imgH, out.height)
        val stats = deltaStats(bg, out)
        assertTrue(stats.changedCount > 0, "P1 must paint ink")
        assertBroadRepeat(stats, imgW, imgH)
    }

    // ─── P2 ───────────────────────────────────────────────────────────────

    @Test
    fun p2_text_cjk_stroke_bold_repeat_alpha128_weakerThan255() {
        val bg = opaqueBg()
        val reducedCfg = WaterMark.default.copy(
            text = "请勿转载",
            textSize = 32f,
            textStyle = TextPaintStyle.Stroke,
            textTypeface = TextTypeface.Bold,
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            hGap = 0,
            vGap = 0,
            alpha = 128,
            markMode = WatermarkMode.Text,
        )
        val fullCfg = reducedCfg.copy(alpha = 255)
        val full = CommonWatermarkPipeline.compose(bg, fullCfg, env, null, 0.5f, 0.5f)
        val reduced = CommonWatermarkPipeline.compose(bg, reducedCfg, env, null, 0.5f, 0.5f)
        val fullStats = deltaStats(bg, full)
        assertTrue(fullStats.changedCount > 0, "P2 alpha-255 CJK control must paint")
        assertNotTofuCjk(bg, fullCfg, full, fullStats)
        assertBroadRepeat(fullStats, imgW, imgH)
        val reducedStats = deltaStats(bg, reduced)
        assertTrue(reducedStats.changedCount > 0, "P2 alpha-128 must remain visible")
        assertBroadRepeat(reducedStats, imgW, imgH)
        assertAlphaReduced(bg, full, reduced)
    }

    // ─── P3 / P4 offset pair ──────────────────────────────────────────────

    @Test
    fun p3_text_multiline_bolditalic_clamp_offset_017_083_localized() {
        val bg = opaqueBg()
        val config = p3Config()
        val out = CommonWatermarkPipeline.compose(bg, config, env, null, 0.17f, 0.83f)
        assertEquals(imgW, out.width)
        assertEquals(imgH, out.height)
        val stats = deltaStats(bg, out)
        assertTrue(stats.changedCount > 0, "P3 must paint multiline ink")
        assertLocalizedClamp(stats, imgW, imgH)
        assertMultilineBothLinesViaSingleLineControls(bg, config, 0.17f, 0.83f, out)
        writeWitness(out, "text-clamp-p3.png")
    }

    @Test
    fun p4_text_multiline_bolditalic_clamp_offset_083_017_centroidMoves() {
        val bg = opaqueBg()
        val config = p3Config()
        val p3 = CommonWatermarkPipeline.compose(bg, config, env, null, 0.17f, 0.83f)
        val p4 = CommonWatermarkPipeline.compose(bg, config, env, null, 0.83f, 0.17f)
        val s3 = deltaStats(bg, p3)
        val s4 = deltaStats(bg, p4)
        assertLocalizedClamp(s3, imgW, imgH)
        assertLocalizedClamp(s4, imgW, imgH)
        val dx = s4.centroidX - s3.centroidX
        val dy = s3.centroidY - s4.centroidY
        assertTrue(
            dx >= 0.20 * imgW,
            "P4 centroid must move right by ≥20% width (dx=$dx, need ${0.20 * imgW})",
        )
        assertTrue(
            dy >= 0.20 * imgH,
            "P4 centroid must move up by ≥20% height (dy=$dy, need ${0.20 * imgH})",
        )
    }

    // ─── P5 ───────────────────────────────────────────────────────────────

    @Test
    fun p5_text_alpha0_equalsBackground_alpha255_visible() {
        val bg = opaqueBg()
        val zeroCfg = WaterMark.default.copy(
            text = "ALPHA",
            textSize = 32f,
            textStyle = TextPaintStyle.Stroke,
            textTypeface = TextTypeface.Italic,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            hGap = 0,
            vGap = 0,
            alpha = 0,
            markMode = WatermarkMode.Text,
        )
        val fullCfg = zeroCfg.copy(alpha = 255)
        val zero = CommonWatermarkPipeline.compose(bg, zeroCfg, env, null, 0.5f, 0.5f)
        val full = CommonWatermarkPipeline.compose(bg, fullCfg, env, null, 0.5f, 0.5f)
        assertPixelEqual(bg, zero, "P5 alpha=0 must be pixel-equal to background")
        val fullStats = deltaStats(bg, full)
        assertTrue(fullStats.changedCount > 0, "P5 alpha=255 control must be visible")
    }

    // ─── P6 / P7 ──────────────────────────────────────────────────────────

    @Test
    fun p6_icon_asymmetric_repeat_alpha255_broad() {
        val bg = opaqueBg()
        val config = sharedIconBaseConfig.copy(
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            alpha = 255,
        )
        val out = CommonWatermarkPipeline.compose(
            bg, config, env, sharedAsymmetricIcon, 0.5f, 0.5f,
        )
        assertEquals(imgW, out.width)
        assertEquals(imgH, out.height)
        val stats = deltaStats(bg, out)
        assertTrue(stats.changedCount > 0, "P6 must paint icon ink")
        assertBroadRepeat(stats, imgW, imgH)
        writeWitness(out, "icon-repeat-p6.png")
    }

    @Test
    fun p7_icon_asymmetric_clamp_offset_017_083_alpha128_weakerThan255() {
        val bg = opaqueBg()
        val reducedCfg = sharedIconBaseConfig.copy(
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            alpha = 128,
        )
        val fullCfg = reducedCfg.copy(alpha = 255)
        val full = CommonWatermarkPipeline.compose(
            bg, fullCfg, env, sharedAsymmetricIcon, 0.17f, 0.83f,
        )
        val reduced = CommonWatermarkPipeline.compose(
            bg, reducedCfg, env, sharedAsymmetricIcon, 0.17f, 0.83f,
        )
        val fullStats = deltaStats(bg, full)
        val reducedStats = deltaStats(bg, reduced)
        assertTrue(fullStats.changedCount > 0, "P7 alpha-255 must paint")
        assertTrue(reducedStats.changedCount > 0, "P7 alpha-128 must remain visible")
        assertLocalizedClamp(fullStats, imgW, imgH)
        assertLocalizedClamp(reducedStats, imgW, imgH)
        assertAlphaReduced(bg, full, reduced)
    }

    // ─── fixtures ─────────────────────────────────────────────────────────

    private fun p3Config(): WaterMark = WaterMark.default.copy(
        text = "TOP\nBOTTOM",
        textSize = 32f,
        textStyle = TextPaintStyle.Fill,
        textTypeface = TextTypeface.BoldItalic,
        tileMode = WatermarkTileMode.CLAMP,
        degree = 0f,
        hGap = 0,
        vGap = 0,
        alpha = 255,
        markMode = WatermarkMode.Text,
    )

    private fun opaqueBg(): ImageBitmap {
        val bmp = ImageBitmap(imgW, imgH, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(imgW.toFloat(), imgH.toFloat()),
        ) {
            drawRect(color = bgColor)
        }
        return bmp
    }

    /** 48×32 asymmetric icon: blue full, red top-left quadrant, white bottom-right mark. */
    private fun asymmetricIcon(): ImageBitmap {
        val w = 48
        val h = 32
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color = Color.Blue)
            drawRect(color = Color.Red, topLeft = Offset.Zero, size = Size(w / 2f, h / 2f))
            drawRect(
                color = Color.White,
                topLeft = Offset(w * 0.75f, h * 0.75f),
                size = Size(w * 0.2f, h * 0.2f),
            )
        }
        return bmp
    }

    private fun writeWitness(bitmap: ImageBitmap, name: String) {
        val dir = File("build/c0-witnesses").apply { mkdirs() }
        val bytes = DesktopWatermarkTextRenderer.encodePng(bitmap)
        File(dir, name).writeBytes(bytes)
    }

    // ─── delta model ──────────────────────────────────────────────────────

    private fun isChanged(out: Color, bg: Color): Boolean =
        abs(out.red - bg.red) > rgbEps ||
            abs(out.green - bg.green) > rgbEps ||
            abs(out.blue - bg.blue) > rgbEps

    private fun deltaStats(background: ImageBitmap, output: ImageBitmap): PixelDeltaStats {
        assertEquals(background.width, output.width, "width mismatch")
        assertEquals(background.height, output.height, "height mismatch")
        val outPx = output.toPixelMap()
        val bgPx = background.toPixelMap()
        val coords = ArrayList<Pair<Int, Int>>()
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var sumX = 0.0
        var sumY = 0.0
        for (y in 0 until outPx.height) {
            for (x in 0 until outPx.width) {
                if (isChanged(outPx[x, y], bgPx[x, y])) {
                    coords.add(x to y)
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    sumX += x
                    sumY += y
                }
            }
        }
        if (coords.isEmpty()) {
            fail("no pixel changed vs background (refusing zero-size bbox)")
        }
        val n = coords.size
        return PixelDeltaStats(
            changedCount = n,
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            centroidX = sumX / n,
            centroidY = sumY / n,
            changedCoords = coords,
        )
    }

    private fun assertBroadRepeat(stats: PixelDeltaStats, width: Int, height: Int) {
        assertTrue(
            stats.bboxW >= (0.60 * width).toInt(),
            "broad REPEAT bboxW=${stats.bboxW} need ≥${0.60 * width}",
        )
        assertTrue(
            stats.bboxH >= (0.60 * height).toInt(),
            "broad REPEAT bboxH=${stats.bboxH} need ≥${0.60 * height}",
        )
        val left = stats.changedCoords.any { it.first < width / 3 }
        val right = stats.changedCoords.any { it.first >= (2 * width) / 3 }
        val top = stats.changedCoords.any { it.second < height / 3 }
        val bottom = stats.changedCoords.any { it.second >= (2 * height) / 3 }
        assertTrue(left && right, "REPEAT needs left+right third ink (L=$left R=$right)")
        assertTrue(top && bottom, "REPEAT needs top+bottom third ink (T=$top B=$bottom)")
    }

    private fun assertLocalizedClamp(stats: PixelDeltaStats, width: Int, height: Int) {
        assertTrue(
            stats.bboxW < (0.60 * width).toInt(),
            "CLAMP bboxW=${stats.bboxW} must be < 60% of width ($width)",
        )
        assertTrue(
            stats.bboxH < (0.60 * height).toInt(),
            "CLAMP bboxH=${stats.bboxH} must be < 60% of height ($height)",
        )
    }

    /**
     * Multiline proof without deriving a mid-bbox split from the result itself.
     *
     * Single-line "TOP" at the same config/offset anchors the first-line Y band.
     * The second line must leave ink **strictly below** that TOP-only maxY — a single
     * line (or tofu strip) cannot satisfy both the first-line band and the below-band.
     */
    private fun assertMultilineBothLinesViaSingleLineControls(
        bg: ImageBitmap,
        multiConfig: WaterMark,
        offsetX: Float,
        offsetY: Float,
        multiOut: ImageBitmap,
    ) {
        val topOnly = CommonWatermarkPipeline.compose(
            bg, multiConfig.copy(text = "TOP"), env, null, offsetX, offsetY,
        )
        val topStats = deltaStats(bg, topOnly)
        val multiStats = deltaStats(bg, multiOut)
        val multiPx = multiOut.toPixelMap()
        val bgPx = bg.toPixelMap()

        fun hasInkInBand(minY: Int, maxY: Int): Boolean {
            if (minY > maxY) return false
            for (y in minY..maxY) {
                for (x in 0 until multiPx.width) {
                    if (isChanged(multiPx[x, y], bgPx[x, y])) return true
                }
            }
            return false
        }

        // First line: ink where single-line TOP paints.
        assertTrue(
            hasInkInBand(topStats.minY, topStats.maxY),
            "multiline must retain ink in TOP single-line Y band [${topStats.minY},${topStats.maxY}]",
        )
        // Second line: ink strictly below TOP-only extent (not mid-bbox of multiline).
        val secondLineMinY = topStats.maxY + 1
        assertTrue(
            multiStats.maxY >= secondLineMinY,
            "multiline maxY=${multiStats.maxY} does not extend below TOP-only maxY=${topStats.maxY}; " +
                "no second-line region",
        )
        assertTrue(
            hasInkInBand(secondLineMinY, multiStats.maxY),
            "multiline must have ink in second-line region y∈[$secondLineMinY,${multiStats.maxY}] " +
                "(strictly below TOP-only band)",
        )
        // Multiline vertical span must exceed single-line TOP (guards against one-line-only paint).
        assertTrue(
            multiStats.bboxH > topStats.bboxH,
            "multiline bboxH=${multiStats.bboxH} must exceed TOP-only bboxH=${topStats.bboxH}",
        )
    }

    /**
     * Reject blank/tofu CJK by rendering each codepoint of `请勿转载` alone under the
     * same config. A repeated missing-glyph box yields pixel-identical single-char
     * outputs; real glyphs must pairwise differ.
     */
    private fun assertNotTofuCjk(
        bg: ImageBitmap,
        cjkCfg: WaterMark,
        cjkOut: ImageBitmap,
        cjkStats: PixelDeltaStats,
    ) {
        assertTrue(cjkStats.changedCount > 0, "full CJK string must paint")
        val chars = listOf("请", "勿", "转", "载")
        val singleOuts = chars.map { ch ->
            CommonWatermarkPipeline.compose(
                bg, cjkCfg.copy(text = ch), env, null, 0.5f, 0.5f,
            )
        }
        for (i in chars.indices) {
            val s = deltaStats(bg, singleOuts[i])
            assertTrue(
                s.changedCount > 0,
                "CJK char '${chars[i]}' must paint ink; blank → GAP per issue 18",
            )
        }
        // Pairwise inequality: identical missing-glyph tofu would match byte-for-byte.
        for (i in chars.indices) {
            for (j in i + 1 until chars.size) {
                assertTrue(
                    !pixelMapsEqual(singleOuts[i], singleOuts[j]),
                    "CJK chars '${chars[i]}' and '${chars[j]}' rendered identically — " +
                        "repeated missing-glyph; mark P2 GAP per issue 18",
                )
            }
        }
        // Full string must not match a pure fullwidth-box control of the same length.
        val boxOut = CommonWatermarkPipeline.compose(
            bg, cjkCfg.copy(text = "□□□□"), env, null, 0.5f, 0.5f,
        )
        assertTrue(
            !pixelMapsEqual(cjkOut, boxOut),
            "full CJK output matches □□□□ control — likely tofu; mark P2 GAP per issue 18",
        )
    }

    private fun pixelMapsEqual(a: ImageBitmap, b: ImageBitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        for (y in 0 until pa.height) {
            for (x in 0 until pa.width) {
                if (pa[x, y].value != pb[x, y].value) return false
            }
        }
        return true
    }

    private fun assertAlphaReduced(background: ImageBitmap, full: ImageBitmap, reduced: ImageBitmap) {
        val fullStats = deltaStats(background, full)
        val mask = fullStats.changedCoords
        assertTrue(mask.isNotEmpty(), "alpha control mask empty")
        val fullPx = full.toPixelMap()
        val reducedPx = reduced.toPixelMap()
        val bgPx = background.toPixelMap()
        var sumFull = 0.0
        var sumReduced = 0.0
        for ((x, y) in mask) {
            val bgC = bgPx[x, y]
            sumFull += rgbDistance(fullPx[x, y], bgC)
            sumReduced += rgbDistance(reducedPx[x, y], bgC)
        }
        val meanFull = sumFull / mask.size
        val meanReduced = sumReduced / mask.size
        assertTrue(meanFull > 0.0, "full alpha mean distance must be > 0")
        assertTrue(meanReduced > 0.0, "reduced alpha must remain visible (mean=$meanReduced)")
        assertTrue(
            meanReduced < meanFull,
            "reduced alpha must be strictly weaker (reduced=$meanReduced full=$meanFull)",
        )
    }

    private fun rgbDistance(c: Color, bg: Color): Double {
        val dr = (c.red - bg.red).toDouble()
        val dg = (c.green - bg.green).toDouble()
        val db = (c.blue - bg.blue).toDouble()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun assertPixelEqual(a: ImageBitmap, b: ImageBitmap, message: String) {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        for (y in 0 until pa.height) {
            for (x in 0 until pa.width) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                if (ca.value != cb.value) {
                    fail("$message at ($x,$y): $ca vs $cb")
                }
            }
        }
    }
}
