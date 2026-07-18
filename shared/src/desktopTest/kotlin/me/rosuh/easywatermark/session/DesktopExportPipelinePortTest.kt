package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adapter-only contract for [DesktopExportPipelinePort]:
 * source validation, unique destination policy, [ImageInfo] dimension mapping, and [Result] mapping.
 *
 * Render/write matrix (Text/Image, JPEG/PNG, REPEAT/CLAMP, alpha, exact-target, missing icon)
 * lives in [me.rosuh.easywatermark.render.DesktopRenderSaveSpineTest] — do not re-assert it here.
 * At most one end-to-end happy path exercises the full port → spine handoff.
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

    /**
     * Sole E2E happy path (one Port → Spine render/write): pre-seed `watermarked.jpg` so unique
     * destination picks `watermarked_1.jpg`, assert sentinel is not overwritten, Result.success,
     * and width/height mutation on [ImageInfo].
     */
    @Test
    fun exportOne_happyPath_uniqueDestination_mapsDimensionsAndResult() = runBlocking {
        val dir = tempDir("happy")
        val source = writeSource(dir)
        val sentinel = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val occupied = File(dir, "watermarked.jpg").apply { writeBytes(sentinel) }
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))

        val result = port.exportOne(info, WaterMark.default, UserPreferences.DEFAULT)

        assertTrue(result.isSuccess(), result.message ?: result.code)
        val out = File(result.data!!.value)
        assertEquals("watermarked_1.jpg", out.name, "unique policy must skip occupied base name")
        assertTrue(out.isFile)
        assertTrue(out.length() > 0)
        assertEquals(64, info.width)
        assertEquals(48, info.height)
        // Pre-existing base name must remain untouched (not overwritten by export).
        assertContentEquals(sentinel, occupied.readBytes())
        assertTrue(occupied.isFile)
    }

    @Test
    fun exportOne_missingSource_mapsToFileNotFound() = runBlocking {
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
    fun exportOne_emptySourcePath_failsWithoutCallingSpine() = runBlocking {
        val dir = tempDir("empty-path")
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val result = port.exportOne(
            ImageInfo(MediaRef("")),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(result.isFailure())
        assertTrue(result.message?.contains("Empty") == true, result.message)
        // No unique watermarked.* file should appear when source validation fails first.
        assertTrue(dir.listFiles()?.none { it.name.startsWith("watermarked") } != false)
    }

    /**
     * Spine throws on blank Image-mode icon; adapter must map the exception to [Result.failure]
     * (not rethrow). Render-level blank-icon message ownership stays on the spine test.
     */
    @Test
    fun exportOne_spineThrow_mapsToResultFailure() = runBlocking {
        val dir = tempDir("spine-throw")
        val source = writeSource(dir)
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef.Empty,
        )
        val result = port.exportOne(info, config, UserPreferences.DEFAULT)
        assertTrue(result.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
        assertEquals(DesktopSaveDecision.EMPTY_ICON_MESSAGE, result.message)
        // Failed export must not mutate dimensions (ImageInfo defaults remain 1×1).
        assertEquals(1, info.width)
        assertEquals(1, info.height)
    }
}
