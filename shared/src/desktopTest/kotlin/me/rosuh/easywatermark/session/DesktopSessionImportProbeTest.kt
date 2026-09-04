package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.render.DesktopWatermarkComposer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSessionImportProbeTest {

    @Test
    fun probeImageSize_readsHeaderWithoutGuessingOneByOne() {
        val f = File.createTempFile("ewm-import-probe-", ".png")
        try {
            f.writeBytes(DesktopWatermarkComposer.sampleBackgroundPng(width = 640, height = 480))
            val (w, h) = DesktopSessionImport.probeImageSize(f)
            assertEquals(640, w)
            assertEquals(480, h)
            val info = DesktopSessionImport.imageInfosFromFiles(listOf(f)).single()
            assertEquals(640, info.width)
            assertEquals(480, info.height)
            assertTrue(info.uri.value.endsWith(f.name))
        } finally {
            f.delete()
        }
    }
}
