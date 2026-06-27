package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d-102: iOS runtime proof that the common [WaterMarkRepository], behind the Swift-facing
 * [IosWatermarkConfigBridge], reads/writes the watermark text through the iOS
 * [createWaterMarkDataStore] (`NSDocumentDirectory`) store. RUNS on `iosSimulatorArm64Test`.
 *
 * A unique store name (NSUUID) is used so the initial read is the true default and the test does not
 * collide with the app's default store or other runs (the simulator data container is ephemeral).
 */
class IosWatermarkConfigBridgeTest {

    private fun bridge(name: String) = IosWatermarkConfigBridge(
        WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = name),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        ),
    )

    @Test
    fun bridge_watermark_text_roundtrip() = runBlocking {
        val b = bridge("s4d102_roundtrip_" + NSUUID().UUIDString())

        // Empty store -> the injected default text.
        assertEquals("EasyWatermark 水印", b.currentText(), "default watermark text must be the constant")

        // Write through the shared editor use-case, then read back.
        b.setText("请勿转载")
        assertEquals("请勿转载", b.currentText(), "watermark text must persist after setText")

        // Overwrite again to prove repeated edits persist.
        b.setText("DO NOT REDISTRIBUTE")
        assertEquals("DO NOT REDISTRIBUTE", b.currentText(), "watermark text must persist on re-edit")
    }
}
