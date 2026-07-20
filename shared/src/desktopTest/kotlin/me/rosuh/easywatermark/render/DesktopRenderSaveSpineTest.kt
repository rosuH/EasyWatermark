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
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sole owner of [DesktopRenderSaveSpine] render/write contract:
 * Text/Image, JPEG/PNG, REPEAT/CLAMP, alpha, exact-target path, missing/blank icon,
 * and C2 per-item offset geometry.
 */
class DesktopRenderSaveSpineTest {

    private val bgColor = Color(0xFF203040)
    private val rgbEps = 0.02f

    private fun fixtureBytes(w: Int = 80, h: Int = 60): ByteArray =
        DesktopWatermarkComposer.sampleBackgroundPng(width = w, height = h)

    private fun opaquePng(w: Int, h: Int): ByteArray {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color = bgColor)
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

    private data class Centroid(val x: Double, val y: Double, val count: Int)

    private fun changedCentroid(backgroundPng: ByteArray, outputPng: ByteArray): Centroid {
        val bg = DesktopImageDecoder.decode(backgroundPng).toPixelMap()
        val out = DesktopImageDecoder.decode(outputPng).toPixelMap()
        assertEquals(bg.width, out.width)
        assertEquals(bg.height, out.height)
        var sumX = 0.0
        var sumY = 0.0
        var n = 0
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val bc = bg[x, y]
                val oc = out[x, y]
                if (abs(oc.red - bc.red) > rgbEps ||
                    abs(oc.green - bc.green) > rgbEps ||
                    abs(oc.blue - bc.blue) > rgbEps
                ) {
                    sumX += x
                    sumY += y
                    n++
                }
            }
        }
        if (n == 0) fail("no changed pixels vs background")
        return Centroid(sumX / n, sumY / n, n)
    }

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
        assertEquals(target.absolutePath, saved.output.value)
        assertEquals(ImageFormat.JPEG, saved.format)
        assertEquals(80, saved.width)
        assertEquals(60, saved.height)
        assertEquals(target.length().toInt(), saved.outputByteCount)
        assertTrue(saved.outputByteCount > 0)
        val bytes = target.readBytes()
        assertTrue(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
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
        assertEquals(100, saved.width)
        assertEquals(50, saved.height)
        val bytes = target.readBytes()
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
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
    fun renderAndSave_alpha_affects_output_bytes() {
        val dir = workDir("alpha")
        val bytes = fixtureBytes()
        val opaque = DesktopRenderSaveSpine.renderAndSave(
            bytes,
            request(WaterMark.default.copy(alpha = 255, text = "A"), UserPreferences(ImageFormat.PNG, 100)),
            File(dir, "o.png"),
        )
        val translucent = DesktopRenderSaveSpine.renderAndSave(
            bytes,
            request(WaterMark.default.copy(alpha = 64, text = "A"), UserPreferences(ImageFormat.PNG, 100)),
            File(dir, "t.png"),
        )
        assertNotEquals(
            File(opaque.output.value).readBytes().toList(),
            File(translucent.output.value).readBytes().toList(),
        )
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
        val ab = aFile.readBytes()
        val bb = bFile.readBytes()
        assertEquals(0x89.toByte(), ab[0])
        assertEquals('P'.code.toByte(), ab[1])
        assertEquals(0x89.toByte(), bb[0])
        val ca = changedCentroid(bg, ab)
        val cb = changedCentroid(bg, bb)
        assertTrue(ca.count > 0 && cb.count > 0)
        val dx = cb.x - ca.x
        val dy = ca.y - cb.y
        assertTrue(dx >= 0.20 * w, "text centroid must move right ≥20% width (dx=$dx)")
        assertTrue(dy >= 0.20 * h, "text centroid must move up ≥20% height (dy=$dy)")
    }

    @Test
    fun renderAndSave_iconClamp_offsetPair_movesCentroid() {
        val w = 320
        val h = 240
        val bg = opaquePng(w, h)
        val dir = workDir("icon-offset")
        val icon = File(dir, "icon.png").apply { writeBytes(asymmetricIconPng()) }
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(icon.absolutePath),
            textSize = 14f,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            hGap = 0,
            vGap = 0,
            alpha = 255,
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
        assertEquals(h, b.height)
        val ca = changedCentroid(bg, aFile.readBytes())
        val cb = changedCentroid(bg, bFile.readBytes())
        val dx = cb.x - ca.x
        val dy = ca.y - cb.y
        assertTrue(dx >= 0.20 * w, "icon centroid must move right ≥20% width (dx=$dx)")
        assertTrue(dy >= 0.20 * h, "icon centroid must move up ≥20% height (dy=$dy)")
    }
}
