package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopExportPipelinePortTest {

    @Test
    fun exportOne_writesUniqueFile_fromFixtureBytes() = runBlocking {
        val dir = File("build/s4d384-phase3-export-port").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(dir, "source.png")
        source.writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = 64, height = 48))
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val info = ImageInfo(MediaRef(source.absolutePath))
        val result = port.exportOne(info, WaterMark.default, UserPreferences.DEFAULT)
        assertTrue(result.isSuccess(), result.message ?: result.code)
        val out = File(result.data!!.value)
        assertTrue(out.isFile)
        assertTrue(out.length() > 0)
        assertEquals(64, info.width)
        assertEquals(48, info.height)
    }

    @Test
    fun exportOne_missingSource_fails() = runBlocking {
        val dir = File("build/s4d384-phase3-export-port-missing").apply { mkdirs() }
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val result = port.exportOne(
            ImageInfo(MediaRef(File(dir, "nope.png").absolutePath)),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(result.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
    }
}
