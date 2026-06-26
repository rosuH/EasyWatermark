package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral watermark configuration (S4d-60). Moved from Android `:app` to `:shared/commonMain`.
 *
 * All Android coupling that used to live here is now at the Android edge:
 * - `android.graphics.Color.parseColor("#FFB800")` → the literal ARGB int `0xFFFFB800` (byte-identical).
 * - `markMode: WaterMarkRepository.MarkMode` (an `:app` repo type) → the neutral [WatermarkMode].
 * - `obtainTileMode(): android.graphics.Shader.TileMode` → the Android extension
 *   `WaterMark.obtainTileMode()` in `utils/ktx` (still backed by [tileMode] + `toShaderTileMode()`).
 * - The `@Keep` annotation (androidx, Android-only) was dropped — `WaterMark` is referenced directly
 *   in code (no reflection/serialization-by-class-name), so R8 keeps it without an annotation.
 *
 * The field order, names, types of persisted-backed fields, and the default values are unchanged, so
 * DataStore storage is byte-identical (no migration) and the renderer reads the same values.
 */
data class WaterMark(
    val text: String,
    val textSize: Float,
    val textColor: Int,
    val textStyle: TextPaintStyle,
    val textTypeface: TextTypeface,
    val alpha: Int,
    val degree: Float,
    val hGap: Int,
    val vGap: Int,
    val iconUri: MediaRef,
    val markMode: WatermarkMode,
    val enableBounds: Boolean,
    val tileMode: WatermarkTileMode,
) {
    companion object {
        val default = WaterMark(
            text = "👋 DO NOT REDISTRIBUTE",
            textSize = (14f).coerceAtLeast(1f),
            // android.graphics.Color.parseColor("#FFB800") == 0xFFFFB800 (alpha FF prepended).
            textColor = 0xFFFFB800.toInt(),
            textStyle = TextPaintStyle.obtainSealedClass(0),
            textTypeface = TextTypeface.obtainSealedClass(0),
            alpha = 255,
            degree = 315f,
            hGap = 0,
            vGap = 0,
            iconUri = MediaRef.Empty,
            markMode = WatermarkMode.Text,
            enableBounds = false,
            tileMode = WatermarkTileMode.REPEAT,
        )
    }
}
