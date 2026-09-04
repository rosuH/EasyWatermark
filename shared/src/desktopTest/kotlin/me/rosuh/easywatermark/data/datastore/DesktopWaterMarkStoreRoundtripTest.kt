package me.rosuh.easywatermark.data.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.domain.WatermarkConfigEditor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof on a real (non-Android) runtime that the commonMain [WaterMarkRepository] + * [WatermarkConfigEditor] work over a desktop-created **watermark-config** DataStore
 * ([createWaterMarkDataStore]). Mirrors [UserConfigDataStoreRoundtripTest] for the watermark store: the
 * injected default text shows on an empty store, then edits round-trip through the shared editor.
 */
class DesktopWaterMarkStoreRoundtripTest {

    @Test
    fun desktop_store_watermark_config_roundtrip() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "s4d120-wm-${System.nanoTime()}")
        try {
            val repo = WaterMarkRepository(
                dataStore = createWaterMarkDataStore(dir),
                defaultTextProvider = { "EasyWatermark 水印" },
                tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
                logError = {},
            )
            val editor = WatermarkConfigEditor(repo)

            // Empty store -> the injected default text (and default REPEAT tile mode).
            val initial = repo.waterMark.first()
            assertEquals("EasyWatermark 水印", initial.text)
            assertEquals(WatermarkTileMode.REPEAT, initial.tileMode)

            // Edits round-trip through the shared editor + the desktop store.
            editor.updateText("请勿转载")
            editor.updateDegree(330f)
            editor.updateTextSize(20f)

            val updated = repo.waterMark.first()
            assertEquals("请勿转载", updated.text)
            assertEquals(330f, updated.degree)
            assertEquals(20f, updated.textSize)
        } finally {
            dir.deleteRecursively()
        }
    }
}
