package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral watermark tile mode (CMP plan S1). Lives in `:shared/commonMain` alongside
 * [ImageFormat] — the model-layer representation of how the watermark cell is laid across the image,
 * replacing the `android.graphics.Shader.TileMode` that previously leaked into [WaterMark]. The
 * Android `Shader.TileMode` now appears only at the render/Android edge (see
 * `WaterMark.obtainTileMode()` and the `utils/ktx` `toShaderTileMode()` mapper).
 *
 * [storageId] is the stable value persisted in DataStore. It is kept EQUAL to the historical
 * `android.graphics.Shader.TileMode` ordinals (CLAMP=0, REPEAT=1, MIRROR=2, DECAL=3) that the old
 * write path stored via `mode.ordinal`, so existing user preferences round-trip with NO migration
 * write. Using an explicit id (not `.ordinal`) makes the persisted contract independent of enum
 * declaration order — the same robustness rule as [ImageFormat] (plan R6: explicit id, not fragile
 * cross-enum ordinal equality).
 *
 * Product note: the UI exposes only [REPEAT] and a single-decal product mode. That product "decal"
 * mode has always been backed by `Shader.TileMode.CLAMP` (NOT the API-31 `Shader.TileMode.DECAL`),
 * so [CLAMP] is the value the "decal" button maps to. [MIRROR] and [DECAL] are legacy read-only
 * values that older storage could hold; they are retained so that EVERY previously-readable
 * persisted id maps to the SAME visible behavior. The equivalence
 * `fromStorageId(id).toShaderTileMode() == id.toTileMode()` (the legacy mapper) is pinned for every
 * id by the app-side `WatermarkTileModeMappingTest`.
 */
enum class WatermarkTileMode(val storageId: Int) {
    CLAMP(0),
    REPEAT(1),
    MIRROR(2),
    DECAL(3);

    companion object {
        /**
         * Pure, platform-neutral id round-trip: maps a [storageId] to a mode, unknown / null →
         * [REPEAT]. API-agnostic — `3` always → [DECAL] here.
         *
         * NOTE: Android **legacy DataStore reads must NOT use this** directly. The historical
         * `Int?.toTileMode()` applied an SDK gate (pre-S `3` → REPEAT, both for render and for the
         * `WaterMark.tileMode` value the UI observes). Reproducing that gate is the job of the
         * Android-edge `Int?.toWatermarkTileMode()` in `app/.../utils/ktx`, which `WaterMarkRepository`
         * uses. This neutral mapper is the round-trip primitive (and is correct on platforms where
         * DECAL is always available).
         */
        fun fromStorageId(id: Int?): WatermarkTileMode =
            entries.firstOrNull { it.storageId == id } ?: REPEAT
    }
}
