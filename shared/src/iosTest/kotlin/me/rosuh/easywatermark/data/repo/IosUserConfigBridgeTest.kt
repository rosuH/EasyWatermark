package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.model.ImageFormat
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d-81: iOS runtime proof that the common `UserConfigRepository`, behind the Swift-facing
 * [IosUserConfigBridge], reads/writes user prefs through the iOS `createUserConfigDataStore`
 * (`NSDocumentDirectory`) store. RUNS on `iosSimulatorArm64Test`.
 *
 * A unique store name (NSUUID) is used so the initial read is the true default and the test does not
 * collide with the app's default store or other runs (the simulator data container is ephemeral).
 */
class IosUserConfigBridgeTest {

    @Test
    fun bridge_userconfig_roundtrip() = runBlocking {
        val uniqueName = "s4d81_roundtrip_" + NSUUID().UUIDString()
        val bridge = IosUserConfigBridge(
            UserConfigRepository(createUserConfigDataStore(name = uniqueName)),
        )

        // Empty store -> repository defaults.
        val initial = bridge.currentPreferences()
        assertEquals(ImageFormat.JPEG, initial.outputFormat, "default outputFormat must be JPEG")
        assertEquals(
            UserConfigRepository.DEFAULT_COMPRESS_LEVEL,
            initial.compressLevel,
            "default compressLevel must be 80",
        )

        // Write through the bridge, then read back (60 % 20 == 0 so it is kept, not snapped to default).
        bridge.setOutputFormat(ImageFormat.PNG)
        bridge.setCompressLevel(60)

        val updated = bridge.currentPreferences()
        assertEquals(ImageFormat.PNG, updated.outputFormat, "outputFormat must persist as PNG")
        assertEquals(60, updated.compressLevel, "compressLevel must persist as 60")

        // saveVersionCode write path must not throw on the iOS store.
        bridge.saveVersionCode(123)
    }
}
