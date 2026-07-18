package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct contract tests for [DesktopRenderSaveSpine] — exact-target write, Text/Image,
 * JPEG/PNG, REPEAT/CLAMP, alpha, missing icon. Destination **policy** (unique / temp / default)
 * Is owned by callers and tested separately. */
class DesktopRenderSaveSpineTest {

    private fun fixtureBytes(w: Int = 80, h: Int = 60): ByteArray =
        DesktopWatermarkComposer.sampleBackgroundPng(width = w, height = h)

    private fun workDir(name: String): File =
        File("build/desktop-render-spine-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun iconFile(dir: File): File {
        val f = File(dir, "icon.png")
        f.writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = 24, height = 24))
        return f
    }

    @Test
    fun renderAndSave_text_jpeg_exact_target_writes_metadata() {
        val dir = workDir("text-jpeg")
        val target = File(dir, "exact/out.jpg")
        val prefs = UserPreferences(ImageFormat.JPEG, 80)
        val saved = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = fixtureBytes(),
            config = WaterMark.default.copy(text = "SPINE"),
            prefs = prefs,
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
            config = WaterMark.default,
            prefs = UserPreferences(ImageFormat.PNG, 100),
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
            config = WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = MediaRef(icon.absolutePath),
            ),
            prefs = UserPreferences.DEFAULT,
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
                config = WaterMark.default.copy(
                    markMode = WatermarkMode.Image,
                    iconUri = MediaRef(File(dir, "ghost.png").absolutePath),
                ),
                prefs = UserPreferences.DEFAULT,
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
                config = WaterMark.default.copy(
                    markMode = WatermarkMode.Image,
                    iconUri = MediaRef.Empty,
                ),
                prefs = UserPreferences.DEFAULT,
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
            WaterMark.default.copy(tileMode = WatermarkTileMode.REPEAT, text = "T"),
            UserPreferences(ImageFormat.PNG, 100),
            File(dir, "r.png"),
        )
        val b = DesktopRenderSaveSpine.renderAndSave(
            bytes,
            WaterMark.default.copy(tileMode = WatermarkTileMode.CLAMP, text = "T"),
            UserPreferences(ImageFormat.PNG, 100),
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
            WaterMark.default.copy(alpha = 255, text = "A"),
            UserPreferences(ImageFormat.PNG, 100),
            File(dir, "o.png"),
        )
        val translucent = DesktopRenderSaveSpine.renderAndSave(
            bytes,
            WaterMark.default.copy(alpha = 64, text = "A"),
            UserPreferences(ImageFormat.PNG, 100),
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
        // Pre-create a file that unique-policy would skip — exact write still uses this path.
        File(dir, "watermarked.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val saved = DesktopRenderSaveSpine.renderAndSave(
            fixtureBytes(),
            WaterMark.default,
            UserPreferences.DEFAULT,
            target,
        )
        assertEquals(target.absolutePath, saved.output.value)
        assertTrue(target.isFile)
        assertTrue(target.length() > 3)
    }
}
