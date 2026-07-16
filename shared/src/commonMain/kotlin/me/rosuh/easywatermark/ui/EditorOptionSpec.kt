package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.data.model.WatermarkConfigRules

/**
 * Platform-neutral editor carousel option (U1).
 *
 * Labels/icons stay at the platform edge (Android resources, Desktop/iOS string bags).
 * Ranges match Android production sliders via [WatermarkConfigRules].
 */
data class EditorOptionSpec(
    val type: FuncType,
    val valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
)

/**
 * Android-parity Content / Style / Layout option catalogs (same order as production
 * `contentFunList` / `styleFunList` / `layoutFunList`).
 */
object EditorOptionCatalog {
    val content: List<EditorOptionSpec> = listOf(
        EditorOptionSpec(FuncType.Text),
        EditorOptionSpec(FuncType.Icon),
    )

    val style: List<EditorOptionSpec> = listOf(
        EditorOptionSpec(FuncType.TileMode),
        EditorOptionSpec(
            FuncType.TextSize,
            valueRange = WatermarkConfigRules.MIN_TEXT_SIZE..WatermarkConfigRules.MAX_TEXT_SIZE,
        ),
        EditorOptionSpec(FuncType.TextTypeFace),
        EditorOptionSpec(FuncType.Color),
        EditorOptionSpec(FuncType.Alpha),
        EditorOptionSpec(
            FuncType.Degree,
            valueRange = 0f..WatermarkConfigRules.MAX_DEGREE,
        ),
    )

    val layout: List<EditorOptionSpec> = listOf(
        EditorOptionSpec(
            FuncType.Horizon,
            valueRange = 0f..WatermarkConfigRules.MAX_HORIZONTAL_GAP.toFloat(),
        ),
        EditorOptionSpec(
            FuncType.Vertical,
            valueRange = 0f..WatermarkConfigRules.MAX_VERTICAL_GAP.toFloat(),
        ),
    )
}
