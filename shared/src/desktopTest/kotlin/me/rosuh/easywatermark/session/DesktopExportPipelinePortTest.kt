package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.DesktopRenderRequest
import me.rosuh.easywatermark.render.DesktopRenderSaveSpine
import me.rosuh.easywatermark.render.DesktopSaveDecision
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adapter-only contract for [DesktopExportPipelinePort]:
 * source validation, unique destination policy, [ImageInfo] dimension mapping, and typed
 * [ExportOutcome] mapping (D1).
 *
 * Exactly one success E2E proves Port → Spine handoff, unique naming, and C2 offset parity with
 * a Spine preview at the same frozen offset (lossless PNG pixel equality).
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
     * Sole E2E happy path: unique destination + offset snapshot + preview/export pixel parity.
     * Pre-seed `watermarked.png` so unique naming picks `watermarked_1.png`.
     */
    @Test
    fun exportOne_happyPath_uniqueDestination_preservesOffset_andMatchesPreviewPixels() = runBlocking {
        val dir = tempDir("happy-offset")
        val source = writeSource(dir, 96, 72)
        val sourceBytes = source.readBytes()
        val sentinel = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val occupied = File(dir, "watermarked.png").apply { writeBytes(sentinel) }
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val config = WaterMark.default.copy(
            text = "PORT",
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 28f,
            degree = 0f,
            alpha = 255,
        )
        val offsetX = 0.17f
        val offsetY = 0.83f
        val previewTarget = File(dir, "preview.png")
        val preview = DesktopRenderSaveSpine.renderAndSave(
            imageBytes = sourceBytes,
            request = DesktopRenderRequest(config, prefs, offsetX, offsetY),
            target = previewTarget,
        )
        assertTrue(previewTarget.isFile)

        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(
            uri = MediaRef(source.absolutePath),
            offsetX = offsetX,
            offsetY = offsetY,
        )
        val result = port.exportOne(info, config, prefs)

        assertTrue(result.isSuccess(), (result as? ExportOutcome.Failure)?.failure?.message)
        val media = (result as ExportOutcome.Success).media
        val out = File(media.ref.value)
        assertEquals("watermarked_1.png", out.name, "unique policy must skip occupied base name")
        assertTrue(out.isFile)
        assertTrue(out.length() > 0)
        assertEquals(96, info.width)
        assertEquals(72, info.height)
        assertEquals(96, media.width)
        assertEquals(72, media.height)
        assertEquals(ImageFormat.PNG, media.format)
        assertEquals(out.length(), media.byteCount)
        assertContentEquals(sentinel, occupied.readBytes())
        // Lossless PNG: Port export must match Spine preview for same request/offset.
        assertContentEquals(previewTarget.readBytes(), out.readBytes())
        assertEquals(preview.width, info.width)
        assertEquals(preview.height, info.height)
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
        val failure = (result as ExportOutcome.Failure).failure
        assertIs<ExportFailure.SourceDecode>(failure)
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, failure.legacyCode)
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
        val failure = (result as ExportOutcome.Failure).failure
        assertIs<ExportFailure.SourceDecode>(failure)
        assertTrue(failure.message?.contains("Empty") == true, failure.message)
        assertTrue(dir.listFiles()?.none { it.name.startsWith("watermarked") } != false)
    }

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
        val failure = (result as ExportOutcome.Failure).failure
        assertIs<ExportFailure.Render>(failure)
        assertEquals(ExportErrorCodes.RENDER, failure.legacyCode)
        assertEquals(DesktopSaveDecision.EMPTY_ICON_MESSAGE, failure.message)
        assertEquals(1, info.width)
        assertEquals(1, info.height)
    }
}
