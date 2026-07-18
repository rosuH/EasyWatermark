package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Neutral (no-Android) contract for [WatermarkTileMode] — runs on every `:shared` target via
 * `:shared:desktopTest` (CMP plan C1.8). The Android-edge composition (`toShaderTileMode()` and
 * Equivalence with the legacy `Int?.toTileMode()`) is pinned separately in the app-side * `WatermarkTileModeMappingTest`, which needs the real `Shader.TileMode`.
 */
class WatermarkTileModeTest {

    @Test
    fun storageIds_are_stable_and_legacy_android_ordinal_compatible() {
        // Must equal the historical android Shader.TileMode ordinals the old write path stored
        // (CLAMP=0, REPEAT=1, MIRROR=2, DECAL=3) so persisted prefs round-trip with no migration.
        assertEquals(0, WatermarkTileMode.CLAMP.storageId)
        assertEquals(1, WatermarkTileMode.REPEAT.storageId)
        assertEquals(2, WatermarkTileMode.MIRROR.storageId)
        assertEquals(3, WatermarkTileMode.DECAL.storageId)
    }

    @Test
    fun fromStorageId_round_trips_every_known_id() {
        for (m in WatermarkTileMode.entries) {
            assertEquals(m, WatermarkTileMode.fromStorageId(m.storageId))
        }
    }

    @Test
    fun fromStorageId_unknown_or_null_falls_back_to_repeat() {
        assertEquals(WatermarkTileMode.REPEAT, WatermarkTileMode.fromStorageId(null))
        assertEquals(WatermarkTileMode.REPEAT, WatermarkTileMode.fromStorageId(99))
        assertEquals(WatermarkTileMode.REPEAT, WatermarkTileMode.fromStorageId(-1))
    }
}
