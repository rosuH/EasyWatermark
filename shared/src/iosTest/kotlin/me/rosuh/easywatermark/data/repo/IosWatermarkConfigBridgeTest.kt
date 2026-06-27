package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d-102: iOS runtime proof that the common [WaterMarkRepository], behind the Swift-facing
 * [IosWatermarkConfigBridge], reads/writes the watermark text (S4d-102), rotation degree (S4d-103), and
 * tile mode (S4d-104) through the iOS [createWaterMarkDataStore] (`NSDocumentDirectory`) store. RUNS on
 * `iosSimulatorArm64Test`.
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

    @Test
    fun bridge_watermark_degree_roundtrip() = runBlocking {
        val b = bridge("s4d103_degree_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.degree (matches the prior Swift hardcoded 315.0).
        assertEquals(315f, b.currentDegree(), "default degree must be 315 (fresh-install default)")

        // Write through the shared editor use-case, then read back.
        b.setDegree(90f)
        assertEquals(90f, b.currentDegree(), "degree must persist after setDegree")

        // Repeated edit persists.
        b.setDegree(0f)
        assertEquals(0f, b.currentDegree(), "degree must persist on re-edit")

        // Out-of-range write is clamped by the shared WatermarkConfigRules.clampDegree (0..360).
        b.setDegree(400f)
        assertEquals(360f, b.currentDegree(), "degree must clamp to 360 (shared clamp)")
    }

    @Test
    fun bridge_watermark_tilemode_roundtrip() = runBlocking {
        val b = bridge("s4d104_tilemode_" + NSUUID().UUIDString())

        // Empty store -> WaterMark.default.tileMode (matches the prior Swift hardcoded REPEAT).
        assertEquals(WatermarkTileMode.REPEAT, b.currentTileMode(), "default tile mode must be REPEAT")

        // Write through the shared editor use-case, then read back (CLAMP = single decal).
        b.setTileMode(WatermarkTileMode.CLAMP)
        assertEquals(WatermarkTileMode.CLAMP, b.currentTileMode(), "tile mode must persist after setTileMode")

        // Switch back to prove repeated edits persist.
        b.setTileMode(WatermarkTileMode.REPEAT)
        assertEquals(WatermarkTileMode.REPEAT, b.currentTileMode(), "tile mode must persist on re-edit")
    }
}
