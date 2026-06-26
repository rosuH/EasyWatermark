package me.rosuh.easywatermark.utils.ktx

import android.graphics.Shader
import android.os.Build
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode

/**
 * Android render/edge bridge for the platform-neutral [WatermarkTileMode] (S1). The neutral model is
 * API-agnostic; the API-31 (`Build.VERSION_CODES.S`) gate for `Shader.TileMode.DECAL` lives HERE at
 * the Android edge, exactly as the legacy `Int?.toTileMode()` gated it (DECAL only on API >= S, else
 * REPEAT).
 *
 * Behavior-preservation guarantee (pinned for every persisted id by `WatermarkTileModeMappingTest`):
 *
 *   WatermarkTileMode.fromStorageId(id).toShaderTileMode() == id.toTileMode()
 *
 * i.e. the new neutral read path produces the identical `Shader.TileMode` the old read path did, so
 * no render/export behavior changes.
 */
/**
 * Android render/edge bridge for [WaterMark] (S4d-60). The neutral [WaterMark] model (commonMain) holds
 * [WatermarkTileMode]; render/export code still asks for an `android.graphics.Shader.TileMode`. This was
 * the former `WaterMark.obtainTileMode()` member — kept as an extension so all call sites
 * (`WatermarkRenderer`, `MainViewModel.generateImage`, `EditorScreen`) are unchanged aside from the import.
 */
fun WaterMark.obtainTileMode(): Shader.TileMode = tileMode.toShaderTileMode()

fun WatermarkTileMode.toShaderTileMode(): Shader.TileMode = when (this) {
    WatermarkTileMode.CLAMP -> Shader.TileMode.CLAMP
    WatermarkTileMode.REPEAT -> Shader.TileMode.REPEAT
    WatermarkTileMode.MIRROR -> Shader.TileMode.MIRROR
    WatermarkTileMode.DECAL ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Shader.TileMode.DECAL
        else Shader.TileMode.REPEAT
}

/**
 * Android-edge **legacy DataStore read** mapper for the persisted `KEY_TILE_MODE` id (S1 revision).
 * Unlike the pure, API-agnostic [WatermarkTileMode.fromStorageId], this carries the SAME SDK gate as
 * the historical `Int?.toTileMode()`, so the **neutral model value the UI observes** matches the old
 * behavior — not just the rendered `Shader.TileMode`:
 *
 * | stored id | API >= S (31) | API < S |
 * |-----------|---------------|---------|
 * | 0         | CLAMP         | CLAMP   |
 * | 1         | REPEAT        | REPEAT  |
 * | 2         | MIRROR        | MIRROR  |
 * | 3         | DECAL         | **REPEAT** (legacy `toTileMode()` fell through to REPEAT pre-S) |
 * | null/junk | REPEAT        | REPEAT  |
 *
 * Why this matters: pre-S, a stored `3` used to render REPEAT *and* leave `WaterMark.tileMode` =
 * REPEAT, so `TileModeOption` selected the "repeat" button. Mapping it to neutral `DECAL` (as the
 * pure neutral mapper does) would leave the segmented control with NO selection on pre-S — a UI-state
 * regression. This mapper reproduces the legacy model/UI selection exactly; composed with
 * [toShaderTileMode] it also still reproduces the legacy render output. (Pinned by
 * `WatermarkTileModeMappingTest`.)
 *
 * `WaterMarkRepository` reads through THIS mapper; the pure [WatermarkTileMode.fromStorageId] remains
 * the platform-neutral id round-trip primitive (and is correct for platforms where DECAL is always
 * available).
 */
fun Int?.toWatermarkTileMode(): WatermarkTileMode = when {
    this == WatermarkTileMode.CLAMP.storageId -> WatermarkTileMode.CLAMP   // 0
    this == WatermarkTileMode.MIRROR.storageId -> WatermarkTileMode.MIRROR // 2
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        this == WatermarkTileMode.DECAL.storageId -> WatermarkTileMode.DECAL // 3, API >= S only
    else -> WatermarkTileMode.REPEAT // 1, null, pre-S 3, unknown
}
