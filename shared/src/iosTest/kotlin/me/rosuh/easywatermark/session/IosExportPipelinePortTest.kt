package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class IosExportPipelinePortTest {

    @Test
    fun exportOne_missingSource_fails() = runBlocking {
        val port = IosExportPipelinePort()
        val result = port.exportOne(
            ImageInfo(MediaRef("/tmp/ewm_does_not_exist_phase4.png")),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(result.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
    }
}
