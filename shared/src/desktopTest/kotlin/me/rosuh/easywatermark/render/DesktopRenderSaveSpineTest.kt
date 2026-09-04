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
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.DesktopExifTestFixture.BaseHeight
import me.rosuh.easywatermark.render.DesktopExifTestFixture.BaseWidth
import me.rosuh.easywatermark.render.DesktopExifTestFixture.Quad
import me.rosuh.easywatermark.render.DesktopExifTestFixture.brightestQuadrant
import me.rosuh.easywatermark.render.DesktopExifTestFixture.containsExifApp1
import me.rosuh.easywatermark.render.DesktopExifTestFixture.jpegWithOrientation
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sole owner of [DesktopRenderSaveSpine] render/write contracts (C4.2).
 *
 * Proves production-Spine paint, decode, encode, and exact-path write via decoded-pixel / magic
 * sentinels — not Common's exact glyph/tofu oracle and not a second Port matrix.
 */
class DesktopRenderSaveSpineTest {

    private val bgColor = Color(0xFF203040)
    /** ~8/255 on 0–1 channel scale — matches Android Port colorDist threshold. */
    private val rgbEps = 0.032f

    private val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val pngMagic = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun fixtureBytes(w: Int = 80, h: Int = 60): ByteArray =
        DesktopWatermarkComposer.sampleBackgroundPng(width = w, height = h)

    private fun opaquePng(w: Int, h: Int, color: Color = bgColor): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color = color)
        }
        return DesktopWatermarkTextRenderer.encodePng(bmp)
    }

    private fun transparentPng(w: Int, h: Int): ByteArray {
        // Undrawn Argb8888 is fully transparent alpha 0.
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        return DesktopWatermarkTextRenderer.encodePng(bmp)
    }

    private fun highEntropyPng(w: Int, h: Int): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            var s = 0xC0FFEEL
            for (y in 0 until h) {
                for (x in 0 until w) {
                    s = (s * 1103515245L + 12345L) and 0x7fffffffL
                    val v = (s % 256).toInt()
                    drawRect(
                        color = Color(v / 255f, ((v * 3) % 256) / 255f, ((v * 7) % 256) / 255f, 1f),
                        topLeft = Offset(x.toFloat(), y.toFloat()),
                        size = Size(1f, 1f),
                    )
                }
            }
        }
        return DesktopWatermarkTextRenderer.encodePng(bmp)
    }

    private fun asymmetricIconPng(): ByteArray {
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
        return DesktopWatermarkTextRenderer.encodePng(bmp)
    }

    private fun workDir(name: String): File =
        File("build/desktop-render-spine-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun iconFile(dir: File): File {
        val f = File(dir, "icon.png")
        f.writeBytes(asymmetricIconPng())
        return f
    }

    private fun request(
        config: WaterMark,
        prefs: UserPreferences = UserPreferences.DEFAULT,
        offsetX: Float = 0.5f,
        offsetY: Float = 0.5f,
    ) = DesktopRenderRequest(config, prefs, offsetX, offsetY)

    private fun assertJpegMagic(bytes: ByteArray) {
        assertTrue(bytes.size >= 3, "JPEG too short")
        assertEquals(jpegMagic[0], bytes[0], "JPEG magic[0]")
        assertEquals(jpegMagic[1], bytes[1], "JPEG magic[1]")
        assertEquals(jpegMagic[2], bytes[2], "JPEG magic[2] must be FF (full FF D8 FF)")
    }

    private fun assertPngMagic(bytes: ByteArray) {
        assertTrue(bytes.size >= 8, "PNG too short")
        for (i in pngMagic.indices) {
            assertEquals(pngMagic[i], bytes[i], "PNG magic[$i]")
        }
    }

    private data class DeltaStats(
        val changedCount: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val centroidX: Double,
        val centroidY: Double,
        val coords: List<Pair<Int, Int>>,
    ) {
        val bboxW: Int get() = if (changedCount == 0) 0 else maxX - minX + 1
        val bboxH: Int get() = if (changedCount == 0) 0 else maxY - minY + 1
    }

    private fun colorDist(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        val da = a.alpha - b.alpha
        return sqrt(dr * dr + dg * dg + db * db + da * da)
    }

    private fun deltaVsBg(backgroundPng: ByteArray, outputBytes: ByteArray): DeltaStats {
        val bg = DesktopImageDecoder.decode(backgroundPng).toPixelMap()
        val out = DesktopImageDecoder.decode(outputBytes).toPixelMap()
        assertEquals(bg.width, out.width)
        assertEquals(bg.height, out.height)
        var minX = out.width
        var maxX = -1
        var minY = out.height
        var maxY = -1
        var sumX = 0.0
        var sumY = 0.0
        val coords = ArrayList<Pair<Int, Int>>()
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDist(out[x, y], bg[x, y]) > rgbEps) {
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
        val n = coords.size
        return DeltaStats(
            changedCount = n,
            minX = if (n == 0) 0 else minX,
            maxX = if (n == 0) 0 else maxX,
            minY = if (n == 0) 0 else minY,
            maxY = if (n == 0) 0 else maxY,
            centroidX = if (n == 0) 0.0 else sumX / n,
            centroidY = if (n == 0) 0.0 else sumY / n,
            coords = coords,
        )
    }

    private fun bitmapsNearlyEqual(backgroundPng: ByteArray, outputBytes: ByteArray, eps: Float = 0.01f): Boolean {
        val bg = DesktopImageDecoder.decode(backgroundPng).toPixelMap()
        val out = DesktopImageDecoder.decode(outputBytes).toPixelMap()
        if (bg.width != out.width || bg.height != out.height) return false
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDist(out[x, y], bg[x, y]) > eps) return false
            }
        }
        return true
    }

    private fun assertAsymmetricIconMarkers(outputBytes: ByteArray, backgroundPng: ByteArray) {
        val bg = DesktopImageDecoder.decode(backgroundPng).toPixelMap()
        val out = DesktopImageDecoder.decode(outputBytes).toPixelMap()
        var redHits = 0
        var blueHits = 0
        var whiteHits = 0
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val oc = out[x, y]
                val bc = bg[x, y]
                if (colorDist(oc, bc) <= rgbEps) continue
                // Tolerant classes after blend over #203040.
                if (oc.red > 0.55f && oc.red > oc.green + 0.12f && oc.red > oc.blue + 0.12f) redHits++
                if (oc.blue > 0.55f && oc.blue > oc.red + 0.08f && oc.blue > oc.green + 0.08f) blueHits++
                if (oc.red > 0.63f && oc.green > 0.63f && oc.blue > 0.63f) whiteHits++
            }
        }
        assertTrue(redHits > 0, "asymmetric icon red marker missing (hits=$redHits)")
        assertTrue(blueHits > 0, "asymmetric icon blue body missing (hits=$blueHits)")
        assertTrue(whiteHits > 0, "asymmetric icon white marker missing (hits=$whiteHits)")
    }

    // ─── Existing format / error / exact-path / large contracts (strengthened) ───

    @Test
    fun renderAndSave_text_jpeg_exact_target_writes_metadata() {
        val dir = workDir("text-jpeg")
        val target = File(dir, "exact/out.jpg")
        val prefs = UserPreferences(ImageFormat.JPEG, 80)
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = fixtureBytes(),
            request = request(WaterMark.default.copy(text = "SPINE"), prefs),
            target = target,
        )
        assertTrue(target.isFile)
        assertTrue(target.name.endsWith(".jpg"), "JPEG target suffix")
        assertEquals(target.absolutePath, saved.output.value)
        assertEquals(ImageFormat.JPEG, saved.format)
        assertEquals(80, saved.width)
        assertEquals(60, saved.height)
        assertEquals(target.length().toInt(), saved.outputByteCount)
        assertTrue(saved.outputByteCount > 0)
        val bytes = target.readBytes()
        assertJpegMagic(bytes)
        val decoded = DesktopImageDecoder.decode(bytes)
        assertEquals(saved.width, decoded.width)
        assertEquals(saved.height, decoded.height)
    }

    @Test
    fun renderAndSave_text_png_exact_target() {
        val dir = workDir("text-png")
        val target = File(dir, "out.png")
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = fixtureBytes(100, 50),
            request = request(WaterMark.default, UserPreferences(ImageFormat.PNG, 100)),
            target = target,
        )
        assertTrue(target.name.endsWith(".png"), "PNG target suffix")
        assertEquals(100, saved.width)
        assertEquals(50, saved.height)
        val bytes = target.readBytes()
        assertPngMagic(bytes)
        val decoded = DesktopImageDecoder.decode(bytes)
        assertEquals(saved.width, decoded.width)
        assertEquals(saved.height, decoded.height)
    }

    @Test
    fun renderAndSave_icon_mode_over_exact_path() {
        val dir = workDir("icon")
        val icon = iconFile(dir)
        val target = File(dir, "icon-out.jpg")
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = fixtureBytes(),
            request = request(
                WaterMark.default.copy(
                    markMode = WatermarkMode.Image,
                    iconUri = MediaRef(icon.absolutePath),
                ),
            ),
            target = target,
        )
        assertTrue(target.isFile)
        assertEquals(80, saved.width)
        assertEquals(60, saved.height)
        assertJpegMagic(target.readBytes())
    }

    @Test
    fun renderAndSave_missing_icon_file_fails_loudly() {
        val dir = workDir("icon-miss")
        val target = File(dir, "nope.jpg")
        val e = assertFailsWith<IllegalArgumentException> {
            DesktopRenderSaveSpine.renderAndSave(
                imageBytes = fixtureBytes(),
                request = request(
                    WaterMark.default.copy(
                        markMode = WatermarkMode.Image,
                        iconUri = MediaRef(File(dir, "ghost.png").absolutePath),
                    ),
                ),
                target = target,
            )
        }
        assertTrue(e.message!!.contains("missing") || e.message!!.contains("not a regular file"))
        assertTrue(!target.exists())
    }

    @Test
    fun renderAndSave_blank_icon_uri_fails_with_decision_message() {
        val dir = workDir("icon-blank")
        val e = assertFailsWith<IllegalArgumentException> {
            DesktopRenderSaveSpine.renderAndSave(
                imageBytes = fixtureBytes(),
                request = request(
                    WaterMark.default.copy(
                        markMode = WatermarkMode.Image,
                        iconUri = MediaRef.Empty,
                    ),
                ),
                target = File(dir, "x.jpg"),
            )
        }
        assertEquals(DesktopSaveDecision.EMPTY_ICON_MESSAGE, e.message)
    }

    @Test
    fun renderAndSave_clamp_and_repeat_both_write() {
        val dir = workDir("tile")
        val bytes = fixtureBytes()
        val a = DesktopRenderSaveSpine.renderAndSave(
            bytes,
            request(WaterMark.default.copy(tileMode = WatermarkTileMode.REPEAT, text = "T"), UserPreferences(ImageFormat.PNG, 100)),
            File(dir, "r.png"),
        )
        val b = DesktopRenderSaveSpine.renderAndSave(
            bytes,
            request(WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, text = "T"), UserPreferences(ImageFormat.PNG, 100)),
            File(dir, "c.png"),
        )
        assertTrue(File(a.output.value).isFile)
        assertTrue(File(b.output.value).isFile)
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
    }

    @Test
    fun renderAndSave_honors_exact_target_path_not_unique_naming() {
        val dir = workDir("exact")
        val target = File(dir, "user-chosen-name.jpg")
        File(dir, "watermarked.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val saved = DesktopRenderSaveSpine.renderAndSave(
            fixtureBytes(),
            request(WaterMark.default),
            target,
        )
        assertEquals(target.absolutePath, saved.output.value)
        assertTrue(target.isFile)
        assertTrue(target.length() > 3)
    }

    @Test
    fun renderAndSave_large2048x1536_completesWithOriginalDimensions() {
        val dir = workDir("large-current-contract")
        val target = File(dir, "large.jpg")
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = fixtureBytes(2048, 1536),
            request = request(
                WaterMark.default.copy(text = "C0.2 LARGE"),
                UserPreferences(ImageFormat.JPEG, 80),
            ),
            target = target,
        )
        assertTrue(target.isFile)
        assertTrue(saved.outputByteCount > 0)
        assertEquals(2048, saved.width)
        assertEquals(1536, saved.height)
        assertEquals(target.absolutePath, saved.output.value)
        assertJpegMagic(target.readBytes())
        val decoded = DesktopImageDecoder.decode(target.readBytes())
        assertEquals(2048, decoded.width)
        assertEquals(1536, decoded.height)
    }

    // ─── C4.2 production-Spine paint/output sentinels (issue 24 §5) ───

    @Test
    fun renderAndSave_textCjkMultilineRepeat_partialAlpha_isBroad() {
        val w = 320
        val h = 240
        val bg = opaquePng(w, h)
        val dir = workDir("text-cjk-repeat")
        val target = File(dir, "out.png")
        val config = WaterMark.default.copy(
            text = "请勿转载\n第二行",
            textSize = 28f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            alpha = 128,
            hGap = 0,
            vGap = 0,
        )
        val saved = DesktopRenderSaveSpine.renderAndSave(
            bg,
            request(config, UserPreferences(ImageFormat.PNG, 100)),
            target,
        )
        assertEquals(w, saved.width)
        assertEquals(h, saved.height)
        assertPngMagic(target.readBytes())
        val stats = deltaVsBg(bg, target.readBytes())
        assertTrue(stats.changedCount > 50, "CJK multiline REPEAT must paint ink (n=${stats.changedCount})")
        assertTrue(
            stats.bboxW >= (0.55 * w).toInt(),
            "REPEAT bboxW broad (bboxW=${stats.bboxW})",
        )
        assertTrue(
            stats.bboxH >= (0.55 * h).toInt(),
            "REPEAT bboxH broad (bboxH=${stats.bboxH})",
        )
    }

    @Test
    fun renderAndSave_textClamp_offsetPair_movesCentroid() {
        val w = 320
        val h = 240
        val bg = opaquePng(w, h)
        val dir = workDir("text-offset")
        val config = WaterMark.default.copy(
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
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val aFile = File(dir, "a.png")
        val bFile = File(dir, "b.png")
        val a = DesktopRenderSaveSpine.renderAndSave(
            bg, request(config, prefs, 0.17f, 0.83f), aFile,
        )
        val b = DesktopRenderSaveSpine.renderAndSave(
            bg, request(config, prefs, 0.83f, 0.17f), bFile,
        )
        assertEquals(w, a.width)
        assertEquals(h, a.height)
        assertEquals(w, b.width)
        assertEquals(h, b.height)
        assertPngMagic(aFile.readBytes())
        assertPngMagic(bFile.readBytes())
        val ca = deltaVsBg(bg, aFile.readBytes())
        val cb = deltaVsBg(bg, bFile.readBytes())
        assertTrue(ca.changedCount > 0 && cb.changedCount > 0)
        // Localized CLAMP (not full-canvas REPEAT)
        assertTrue(ca.bboxW < 0.60 * w, "CLAMP A localized W (bboxW=${ca.bboxW})")
        assertTrue(ca.bboxH < 0.60 * h, "CLAMP A localized H (bboxH=${ca.bboxH})")
        assertTrue(cb.bboxW < 0.60 * w, "CLAMP B localized W")
        assertTrue(cb.bboxH < 0.60 * h, "CLAMP B localized H")
        val dx = cb.centroidX - ca.centroidX
        val dy = ca.centroidY - cb.centroidY
        assertTrue(dx >= 0.20 * w, "text centroid must move right ≥20% width (dx=$dx)")
        assertTrue(dy >= 0.20 * h, "text centroid must move up ≥20% height (dy=$dy)")
    }

    @Test
    fun renderAndSave_textClamp_alpha0EqualsBackground_alpha255Visible() {
        val w = 320
        val h = 240
        val bg = opaquePng(w, h)
        val dir = workDir("text-alpha")
        val base = WaterMark.default.copy(
            text = "Alpha",
            textSize = 36f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            hGap = 0,
            vGap = 0,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val a0File = File(dir, "a0.png")
        val a255File = File(dir, "a255.png")
        DesktopRenderSaveSpine.renderAndSave(
            bg, request(base.copy(alpha = 0), prefs), a0File,
        )
        DesktopRenderSaveSpine.renderAndSave(
            bg, request(base.copy(alpha = 255), prefs), a255File,
        )
        assertTrue(
            bitmapsNearlyEqual(bg, a0File.readBytes()),
            "alpha0 must match decoded background on every pixel",
        )
        val s255 = deltaVsBg(bg, a255File.readBytes())
        assertTrue(s255.changedCount > 0, "alpha255 must be visible")
    }

    @Test
    fun renderAndSave_iconRepeatAndClamp_preserveBreadthOffsetAndAlpha() {
        val w = 320
        val h = 240
        val bg = opaquePng(w, h)
        val dir = workDir("icon-matrix")
        val icon = File(dir, "icon.png").apply { writeBytes(asymmetricIconPng()) }
        val base = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(icon.absolutePath),
            textSize = 14f,
            hGap = 0,
            vGap = 0,
            degree = 0f,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)

        val repFile = File(dir, "repeat.png")
        DesktopRenderSaveSpine.renderAndSave(
            bg,
            request(base.copy(tileMode = WatermarkTileMode.REPEAT, alpha = 255), prefs),
            repFile,
        )
        val sRep = deltaVsBg(bg, repFile.readBytes())
        assertTrue(sRep.changedCount > 100, "icon REPEAT must paint (n=${sRep.changedCount})")
        assertTrue(sRep.bboxW >= (0.55 * w).toInt(), "icon REPEAT bboxW")
        assertTrue(sRep.bboxH >= (0.55 * h).toInt(), "icon REPEAT bboxH")

        val clamp255File = File(dir, "clamp255.png")
        val clamp128File = File(dir, "clamp128.png")
        DesktopRenderSaveSpine.renderAndSave(
            bg,
            request(base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 255), prefs, 0.17f, 0.83f),
            clamp255File,
        )
        DesktopRenderSaveSpine.renderAndSave(
            bg,
            request(base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 128), prefs, 0.17f, 0.83f),
            clamp128File,
        )
        val s255 = deltaVsBg(bg, clamp255File.readBytes())
        assertTrue(s255.changedCount > 0, "icon CLAMP must paint")
        assertTrue(s255.bboxW < 0.60 * w, "CLAMP localized W")
        assertTrue(s255.bboxH < 0.60 * h, "CLAMP localized H")
        assertTrue(s255.centroidX < 0.45 * w, "CLAMP offset left (cx=${s255.centroidX})")
        assertTrue(s255.centroidY > 0.55 * h, "CLAMP offset lower (cy=${s255.centroidY})")
        assertAsymmetricIconMarkers(clamp255File.readBytes(), bg)

        val s128 = deltaVsBg(bg, clamp128File.readBytes())
        assertTrue(s128.changedCount > 0, "alpha128 CLAMP must remain visible")
        assertTrue(s128.bboxW < 0.60 * w)
        assertTrue(s128.bboxH < 0.60 * h)
        val cdx = abs(s128.centroidX - s255.centroidX)
        val cdy = abs(s128.centroidY - s255.centroidY)
        assertTrue(
            cdx < 0.12 * w && cdy < 0.12 * h,
            "alpha128 centroid must match alpha255 location (dx=$cdx dy=$cdy)",
        )

        val bgPx = DesktopImageDecoder.decode(bg).toPixelMap()
        val px255 = DesktopImageDecoder.decode(clamp255File.readBytes()).toPixelMap()
        val px128 = DesktopImageDecoder.decode(clamp128File.readBytes()).toPixelMap()
        var sum255 = 0.0
        var sum128 = 0.0
        var n = 0
        for ((x, y) in s255.coords) {
            sum255 += colorDist(px255[x, y], bgPx[x, y]).toDouble()
            sum128 += colorDist(px128[x, y], bgPx[x, y]).toDouble()
            n++
        }
        assertTrue(n > 0)
        val mean128 = sum128 / n
        val mean255 = sum255 / n
        assertTrue(mean128 > 0.004, "alpha128 mask mean must be nonzero (visible)")
        assertTrue(
            mean128 < mean255 * 0.95,
            "alpha128 must be weaker than alpha255 on shared mask (m128=$mean128 m255=$mean255)",
        )

        // Offset pair still moves centroid (retain C2 geometry ownership inside this owner).
        val bFile = File(dir, "clamp-b.png")
        DesktopRenderSaveSpine.renderAndSave(
            bg,
            request(base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 255), prefs, 0.83f, 0.17f),
            bFile,
        )
        val sb = deltaVsBg(bg, bFile.readBytes())
        val dx = sb.centroidX - s255.centroidX
        val dy = s255.centroidY - sb.centroidY
        assertTrue(dx >= 0.20 * w, "icon centroid must move right ≥20% width (dx=$dx)")
        assertTrue(dy >= 0.20 * h, "icon centroid must move up ≥20% height (dy=$dy)")
    }

    @Test
    fun renderAndSave_orientation7_jpegAndPng_haveExactMagicUprightDimsAndNoExif() {
        val dir = workDir("orient7")
        val oriented = jpegWithOrientation(7)
        // Source must carry EXIF APP1 so strip assertion is meaningful.
        assertTrue(containsExifApp1(oriented), "fixture must contain EXIF APP1")
        assertEquals(7, DesktopImageDecoder.parseExifOrientation(oriented))

        val textCfg = WaterMark.default.copy(
            text = "O7",
            textSize = 10f,
            alpha = 0,
            markMode = WatermarkMode.Text,
        )

        val jpegTarget = File(dir, "out.jpg")
        val jpegSaved = DesktopRenderSaveSpine.renderAndSave(
            oriented,
            request(textCfg, UserPreferences(ImageFormat.JPEG, 90)),
            jpegTarget,
        )
        assertTrue(jpegTarget.name.endsWith(".jpg"))
        val jpegBytes = jpegTarget.readBytes()
        assertJpegMagic(jpegBytes)
        assertEquals(BaseHeight, jpegSaved.width, "orientation-7 JPEG upright width (swapped)")
        assertEquals(BaseWidth, jpegSaved.height, "orientation-7 JPEG upright height (swapped)")
        val jpegDecoded = DesktopImageDecoder.decode(jpegBytes)
        assertEquals(BaseHeight, jpegDecoded.width)
        assertEquals(BaseWidth, jpegDecoded.height)
        assertEquals(Quad.BR, brightestQuadrant(jpegDecoded), "orientation 7 → bright BR after Spine")
        assertTrue(
            !containsExifApp1(jpegBytes),
            "Spine JPEG must strip source EXIF APP1 orientation payload",
        )
        assertEquals(1, DesktopImageDecoder.parseExifOrientation(jpegBytes))

        val pngTarget = File(dir, "out.png")
        val pngSaved = DesktopRenderSaveSpine.renderAndSave(
            oriented,
            request(textCfg, UserPreferences(ImageFormat.PNG, 100)),
            pngTarget,
        )
        assertTrue(pngTarget.name.endsWith(".png"))
        val pngBytes = pngTarget.readBytes()
        assertPngMagic(pngBytes)
        assertEquals(BaseHeight, pngSaved.width)
        assertEquals(BaseWidth, pngSaved.height)
        val pngDecoded = DesktopImageDecoder.decode(pngBytes)
        assertEquals(BaseHeight, pngDecoded.width)
        assertEquals(BaseWidth, pngDecoded.height)
        assertEquals(Quad.BR, brightestQuadrant(pngDecoded), "orientation 7 PNG → bright BR after Spine")
    }

    @Test
    fun renderAndSave_jpegQuality_q20NotLargerThanQ95_sameDimensions() {
        val dir = workDir("jpeg-q")
        val entropy = highEntropyPng(256, 192)
        val config = WaterMark.default.copy(
            text = "Q",
            textSize = 20f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.REPEAT,
            alpha = 255,
        )
        val q20File = File(dir, "q20.jpg")
        val q95File = File(dir, "q95.jpg")
        val s20 = DesktopRenderSaveSpine.renderAndSave(
            entropy, request(config, UserPreferences(ImageFormat.JPEG, 20)), q20File,
        )
        val s95 = DesktopRenderSaveSpine.renderAndSave(
            entropy, request(config, UserPreferences(ImageFormat.JPEG, 95)), q95File,
        )
        assertEquals(s95.width, s20.width)
        assertEquals(s95.height, s20.height)
        val b20 = q20File.readBytes()
        val b95 = q95File.readBytes()
        assertJpegMagic(b20)
        assertJpegMagic(b95)
        val d20 = DesktopImageDecoder.decode(b20)
        val d95 = DesktopImageDecoder.decode(b95)
        assertEquals(d95.width, d20.width)
        assertEquals(d95.height, d20.height)
        assertTrue(
            b20.size <= b95.size,
            "q20 must not be larger than q95 (q20=${b20.size} q95=${b95.size})",
        )
    }

    @Test
    fun renderAndSave_transparentSourceToJpeg_flattensOpaqueWhite() {
        val dir = workDir("white-flatten")
        val src = transparentPng(64, 48)
        val config = WaterMark.default.copy(
            text = "T",
            textSize = 12f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            alpha = 0,
        )
        val target = File(dir, "flat.jpg")
        val saved = DesktopRenderSaveSpine.renderAndSave(
            src, request(config, UserPreferences(ImageFormat.JPEG, 90)), target,
        )
        assertEquals(64, saved.width)
        assertEquals(48, saved.height)
        val bytes = target.readBytes()
        assertJpegMagic(bytes)
        val px = DesktopImageDecoder.decode(bytes).toPixelMap()
        val samples = listOf(
            0 to 0,
            px.width - 1 to 0,
            0 to px.height - 1,
            px.width - 1 to px.height - 1,
            px.width / 2 to px.height / 2,
        )
        for ((x, y) in samples) {
            val c = px[x, y]
            assertTrue(
                c.red > 0.78f && c.green > 0.78f && c.blue > 0.78f,
                "transparent→JPEG must be near-white at ($x,$y): r=${c.red} g=${c.green} b=${c.blue}",
            )
            assertTrue(
                c.alpha > 0.95f,
                "decoded JPEG pixel must be opaque at ($x,$y): a=${c.alpha}",
            )
        }
    }
}
