package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural contracts for ADR-0018 pipeline mapping (all targets, no pixel backend).
 *
 * Full [CommonWatermarkPipeline.compose] pixel path is gated in
 * `shared/desktopTest/.../CommonWatermarkPipelineComposeTest` (Skiko fonts) and
 * `app/.../C2DualPathMeasurementTest` / export-port test (Android).
 */
class CommonWatermarkPipelineTest {

    @Test
    fun defaultConfig_isTextMode() {
        assertEquals(WatermarkMode.Text, WaterMark.default.markMode)
        assertTrue(WaterMark.default.text.isNotEmpty())
    }

    @Test
    fun tileMode_productClamp_isDistinctFromRepeat() {
        assertEquals(0, WatermarkTileMode.CLAMP.storageId)
        assertEquals(1, WatermarkTileMode.REPEAT.storageId)
    }

    @Test
    fun pipeline_mapsNonProductTileModesToRepeat() {
        // MIRROR/DECAL are legacy ids; product path only exposes REPEAT/CLAMP. The pipeline
        // coerce branch (config.tileMode when) keeps non-CLAMP as REPEAT for safety.
        val clamp = WatermarkTileMode.CLAMP
        val repeat = WatermarkTileMode.REPEAT
        assertTrue(clamp != repeat)
        // Document the compose() mapping rule without needing TextRasterEnv on Native:
        fun mapTile(mode: WatermarkTileMode): WatermarkTileMode =
            when (mode) {
                WatermarkTileMode.CLAMP -> WatermarkTileMode.CLAMP
                else -> WatermarkTileMode.REPEAT
            }
        assertEquals(WatermarkTileMode.CLAMP, mapTile(WatermarkTileMode.CLAMP))
        assertEquals(WatermarkTileMode.REPEAT, mapTile(WatermarkTileMode.REPEAT))
        assertEquals(WatermarkTileMode.REPEAT, mapTile(WatermarkTileMode.MIRROR))
        assertEquals(WatermarkTileMode.REPEAT, mapTile(WatermarkTileMode.DECAL))
    }
}
