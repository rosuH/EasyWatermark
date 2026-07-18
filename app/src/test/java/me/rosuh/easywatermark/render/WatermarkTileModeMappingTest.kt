package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Shader
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import me.rosuh.easywatermark.utils.ktx.toShaderTileMode
import me.rosuh.easywatermark.utils.ktx.toTileMode
import me.rosuh.easywatermark.utils.ktx.toWatermarkTileMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S1 behavior-preservation gate. The repository reads `KEY_TILE_MODE` through the Android-edge
 * legacy mapper `Int?.toWatermarkTileMode()`, which must reproduce BOTH halves of the old behavior:
 *
 * 1. the **neutral model value** the UI observes (`WaterMark.tileMode`, what `TileModeOption`
 * selects against) — this is the half the first S1 cut missed for pre-S id=3, and
 * 2. the **rendered** `Shader.TileMode`, i.e. `id.toWatermarkTileMode().toShaderTileMode()` must
 * equal the legacy oracle `id.toTileMode()` for every id.
 *
 * `toTileMode()` (the historical `Int? -> Shader.TileMode` mapper) is retained untouched as the
 * oracle. Robolectric supplies real `Shader.TileMode` ordinals + `Build.VERSION`, so the API-31
 * DECAL gate is exercised on both SDK tiers (post-S at class level; pre-S via method `@Config`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // post-S: id 3 -> DECAL
class WatermarkTileModeMappingTest {

    private val allIds = listOf(null, -1, 0, 1, 2, 3, 4, 99)

    @Test
    fun storageIds_equal_legacy_android_shader_ordinals() {
        // Old write path persisted mode.ordinal; storageId must equal those ordinals (no migration).
        assertEquals(Shader.TileMode.CLAMP.ordinal, WatermarkTileMode.CLAMP.storageId)
        assertEquals(Shader.TileMode.REPEAT.ordinal, WatermarkTileMode.REPEAT.storageId)
        assertEquals(Shader.TileMode.MIRROR.ordinal, WatermarkTileMode.MIRROR.storageId)
        assertEquals(Shader.TileMode.DECAL.ordinal, WatermarkTileMode.DECAL.storageId)
    }

    // ---- render equivalence: repo read path composed to the Android edge == legacy oracle --------

    @Test
    fun repo_read_path_renders_same_as_legacy_for_every_id_post_S() {
        for (id in allIds) {
            assertEquals(
                "post-S id=$id render parity",
                id.toTileMode(),
                id.toWatermarkTileMode().toShaderTileMode(),
            )
        }
    }

    @Test
    @Config(sdk = [30], application = Application::class) // pre-S
    fun repo_read_path_renders_same_as_legacy_for_every_id_pre_S() {
        for (id in allIds) {
            assertEquals(
                "pre-S id=$id render parity",
                id.toTileMode(),
                id.toWatermarkTileMode().toShaderTileMode(),
            )
        }
    }

    // ---- NEUTRAL MODEL VALUE (UI-state) — the half the first cut missed --------------------------

    @Test
    fun post_S_id3_maps_to_neutral_decal() {
        assertEquals(WatermarkTileMode.DECAL, 3.toWatermarkTileMode())
    }

    @Test
    @Config(sdk = [30], application = Application::class) // pre-S: id 3 must be neutral REPEAT
    fun pre_S_id3_maps_to_neutral_repeat_so_ui_selects_repeat() {
        // Legacy: pre-S stored 3 became Shader.TileMode.REPEAT, so WaterMark.tileMode == REPEAT and
        // TileModeOption selected the repeat button. The neutral value must be REPEAT (not DECAL),
        // otherwise the segmented control would select neither of its two options (repeat/decal).
        assertEquals(WatermarkTileMode.REPEAT, 3.toWatermarkTileMode())
        // REPEAT is one of the options TileModeOption offers, so the UI shows a selection.
        val uiOptions = listOf(WatermarkTileMode.REPEAT, WatermarkTileMode.CLAMP)
        assert(uiOptions.contains(3.toWatermarkTileMode())) {
            "pre-S id=3 must land on a selectable UI option"
        }
    }

    @Test
    fun neutral_model_value_table_post_S() {
        assertEquals(WatermarkTileMode.CLAMP, 0.toWatermarkTileMode())
        assertEquals(WatermarkTileMode.REPEAT, 1.toWatermarkTileMode())
        assertEquals(WatermarkTileMode.MIRROR, 2.toWatermarkTileMode())
        assertEquals(WatermarkTileMode.DECAL, 3.toWatermarkTileMode())
        assertEquals(WatermarkTileMode.REPEAT, null.toWatermarkTileMode())
        assertEquals(WatermarkTileMode.REPEAT, 99.toWatermarkTileMode())
    }

    // ---- product modes / write round-trip / default --------------------------------------------

    @Test
    fun product_modes_repeat_and_decal_are_preserved() {
        // UI "repeat" -> REPEAT (stored 1) -> Shader.REPEAT
        assertEquals(1, WatermarkTileMode.REPEAT.storageId)
        assertEquals(Shader.TileMode.REPEAT, WatermarkTileMode.REPEAT.toShaderTileMode())
        // UI "decal" -> CLAMP (stored 0) -> Shader.CLAMP (single-decal product mode, NOT api DECAL)
        assertEquals(0, WatermarkTileMode.CLAMP.storageId)
        assertEquals(Shader.TileMode.CLAMP, WatermarkTileMode.CLAMP.toShaderTileMode())
    }

    @Test
    fun product_writes_round_trip_storage_id_equals_legacy_ordinal() {
        // Repo writes mode.storageId; the only ids the UI can produce (REPEAT=1, CLAMP=0) read back
        // to the same mode through the Android-edge legacy mapper.
        for (mode in listOf(WatermarkTileMode.REPEAT, WatermarkTileMode.CLAMP)) {
            assertEquals(mode, mode.storageId.toWatermarkTileMode())
        }
    }

    @Test
    fun default_watermark_tile_mode_is_repeat() {
        assertEquals(WatermarkTileMode.REPEAT, WaterMark.default.tileMode)
        assertEquals(Shader.TileMode.REPEAT, WaterMark.default.obtainTileMode())
    }
}
