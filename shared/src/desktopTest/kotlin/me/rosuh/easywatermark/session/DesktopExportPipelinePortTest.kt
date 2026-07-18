package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Adapter contract tests for [DesktopExportPipelinePort]
 * (source validation, unique naming, Result mapping). Render/write is on [DesktopRenderSaveSpine].
 */
class DesktopExportPipelinePortTest {

    private fun tempDir(name: String): File =
        File("build/desktop-export-port-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun writeSource(dir: File, w: Int = 64, h: Int = 48): File {
        val source = File(dir, "source.png")
        source.writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = w, height = h))
        return source
    }

    private fun writeIcon(dir: File): File {
        val icon = File(dir, "icon.png")
        icon.writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = 32, height = 32))
        return icon
    }

    @Test
    fun exportOne_writesUniqueFile_fromFixtureBytes() = runBlocking {
        val dir = tempDir("unique")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val result = port.exportOne(info, WaterMark.default, UserPreferences.DEFAULT)
        assertTrue(result.isSuccess(), result.message ?: result.code)
        val out = File(result.data!!.value)
        assertTrue(out.isFile)
        assertTrue(out.length() > 0)
        assertEquals(64, info.width)
        assertEquals(48, info.height)
        // JPEG default prefs → .jpg extension
        assertTrue(out.name.endsWith(".jpg"), out.name)
        assertTrue(out.readBytes().let { it.size >= 2 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() })
    }

    @Test
    fun exportOne_png_format_writes_png_magic() = runBlocking {
        val dir = tempDir("png")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val result = port.exportOne(info, WaterMark.default, prefs)
        assertTrue(result.isSuccess(), result.message ?: result.code)
        val out = File(result.data!!.value)
        assertTrue(out.name.endsWith(".png"), out.name)
        val bytes = out.readBytes()
        assertTrue(bytes.size >= 4)
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals(0x50.toByte(), bytes[1]) // P
        assertEquals(0x4E.toByte(), bytes[2]) // N
        assertEquals(0x47.toByte(), bytes[3]) // G
    }

    @Test
    fun exportOne_unique_destination_does_not_overwrite() = runBlocking {
        val dir = tempDir("collision")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info1 = ImageInfo(MediaRef(source.absolutePath))
        val info2 = ImageInfo(MediaRef(source.absolutePath))
        val r1 = port.exportOne(info1, WaterMark.default, UserPreferences.DEFAULT)
        val r2 = port.exportOne(info2, WaterMark.default, UserPreferences.DEFAULT)
        assertTrue(r1.isSuccess() && r2.isSuccess())
        assertNotEquals(r1.data!!.value, r2.data!!.value)
        assertTrue(File(r1.data!!.value).isFile)
        assertTrue(File(r2.data!!.value).isFile)
    }

    @Test
    fun exportOne_clamp_and_repeat_both_succeed() = runBlocking {
        val dir = tempDir("tile")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        for (tile in listOf(WatermarkTileMode.REPEAT, WatermarkTileMode.CLAMP)) {
            val info = ImageInfo(MediaRef(source.absolutePath))
            val config = WaterMark.default.copy(tileMode = tile)
            val result = port.exportOne(info, config, UserPreferences.DEFAULT)
            assertTrue(result.isSuccess(), "tile=$tile ${result.message}")
            assertEquals(64, info.width)
            assertEquals(48, info.height)
        }
    }

    @Test
    fun exportOne_icon_mode_renders_when_icon_file_present() = runBlocking {
        val dir = tempDir("icon-ok")
        val source = writeSource(dir)
        val icon = writeIcon(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(icon.absolutePath),
        )
        val result = port.exportOne(info, config, UserPreferences.DEFAULT)
        assertTrue(result.isSuccess(), result.message ?: result.code)
        assertTrue(File(result.data!!.value).length() > 0)
    }

    @Test
    fun exportOne_icon_mode_missing_file_fails() = runBlocking {
        val dir = tempDir("icon-miss")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(File(dir, "no-icon.png").absolutePath),
        )
        val result = port.exportOne(info, config, UserPreferences.DEFAULT)
        assertTrue(result.isFailure())
        assertTrue(
            result.message?.contains("missing") == true ||
                result.message?.contains("not a regular file") == true,
            result.message,
        )
    }

    @Test
    fun exportOne_icon_mode_blank_uri_fails() = runBlocking {
        val dir = tempDir("icon-blank")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef.Empty,
        )
        val result = port.exportOne(info, config, UserPreferences.DEFAULT)
        assertTrue(result.isFailure())
    }

    @Test
    fun exportOne_missingSource_fails() = runBlocking {
        val dir = tempDir("missing")
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val result = port.exportOne(
            ImageInfo(MediaRef(File(dir, "nope.png").absolutePath)),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(result.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
    }

    @Test
    fun exportOne_lower_alpha_changes_encoded_bytes() = runBlocking {
        val dir = tempDir("alpha")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val opaque = WaterMark.default.copy(alpha = 255, text = "ALPHA")
        val translucent = WaterMark.default.copy(alpha = 80, text = "ALPHA")
        val info1 = ImageInfo(MediaRef(source.absolutePath))
        val info2 = ImageInfo(MediaRef(source.absolutePath))
        val r1 = port.exportOne(info1, opaque, UserPreferences(ImageFormat.PNG, 100))
        val r2 = port.exportOne(info2, translucent, UserPreferences(ImageFormat.PNG, 100))
        assertTrue(r1.isSuccess() && r2.isSuccess())
        val b1 = File(r1.data!!.value).readBytes()
        val b2 = File(r2.data!!.value).readBytes()
        assertNotEquals(b1.toList(), b2.toList(), "alpha must affect composition bytes")
    }
}
