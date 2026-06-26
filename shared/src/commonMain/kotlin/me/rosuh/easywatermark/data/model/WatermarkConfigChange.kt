package me.rosuh.easywatermark.data.model

import kotlin.math.roundToInt

/**
 * Platform-neutral typed watermark config change command (S4d-72). Replaces `MainViewModel`'s
 * repeated branch-local `any as ...` casts with one typed boundary: [from] maps a ([FuncType], raw
 * value) pair to a typed command; the ViewModel then dispatches the typed command to its existing
 * `update*` methods (which remain the behavior source — e.g. `WatermarkConfigRules.alphaPercentToByte`
 * for alpha, repo clamps for gap/degree/size).
 *
 * Behavior is preserved exactly:
 * - the same casts ([from]'s `value as X`) are fail-fast (a wrong type throws `ClassCastException`,
 *   as the old inline `any as X` did);
 * - horizontal/vertical gaps carry the same `(value as Float).roundToInt()` rounding the old dispatch
 *   passed to `updateHorizon`/`updateVertical`.
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

    companion object {
        /** Map a control [type] + its raw editor value to a typed command. Fail-fast on wrong type
         *  (matches the legacy `any as X` casts). */
        fun from(type: FuncType, value: Any): WatermarkConfigChange = when (type) {
            FuncType.Text -> Text(value as String)
            FuncType.Icon -> Icon(value as MediaRef)
            FuncType.Color -> Color(value as Int)
            FuncType.Alpha -> AlphaPercent(value as Float)
            FuncType.Degree -> Degree(value as Float)
            FuncType.TextSize -> TextSize(value as Float)
            FuncType.TextTypeFace -> Typeface(value as TextTypeface)
            FuncType.TileMode -> TileMode(value as WatermarkTileMode)
            FuncType.Horizon -> HorizontalGap((value as Float).roundToInt())
            FuncType.Vertical -> VerticalGap((value as Float).roundToInt())
        }
    }
}
