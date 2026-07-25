package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral typed watermark config change command (F2 / issue 12 P6).
 *
 * Shared editor controls emit these at the control source. Hosts apply via Session
 * [me.rosuh.easywatermark.session.AppIntent.ApplyConfig] / `applyConfig` only.
 * No Android `Uri` / resource ids — use [MediaRef] for icons.
 *
 * Semantics preserved from the legacy host translator:
 * - [AlphaPercent] is 0..100 (editor slider); `updateAlpha` converts to byte.
 * - [HorizontalGap] / [VerticalGap] are already rounded to `Int` at emission
 *   (`(sliderFloat).roundToInt()` next to Horizon/Vertical controls).
 */
sealed class WatermarkConfigChange {
    data class Text(val text: String) : WatermarkConfigChange()
    data class Icon(val icon: MediaRef) : WatermarkConfigChange()
    data class Color(val color: Int) : WatermarkConfigChange()
    /** Alpha as a 0..100 percent (the editor slider value); converted to a byte by `updateAlpha`. */
    data class AlphaPercent(val percent: Float) : WatermarkConfigChange()
    data class Degree(val degree: Float) : WatermarkConfigChange()
    data class TextSize(val size: Float) : WatermarkConfigChange()
    data class Typeface(val typeface: TextTypeface) : WatermarkConfigChange()
    data class TileMode(val tileMode: WatermarkTileMode) : WatermarkConfigChange()
    /** Horizontal gap, already rounded to the `Int` passed to `updateHorizon`. */
    data class HorizontalGap(val gap: Int) : WatermarkConfigChange()
    /** Vertical gap, already rounded to the `Int` passed to `updateVertical`. */
    data class VerticalGap(val gap: Int) : WatermarkConfigChange()
}
