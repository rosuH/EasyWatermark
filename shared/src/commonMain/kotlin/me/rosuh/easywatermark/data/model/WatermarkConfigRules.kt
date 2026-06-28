package me.rosuh.easywatermark.data.model

// Platform-neutral watermark config normalization (S4d-61). Pure value functions + limit constants,
// extracted verbatim from the Android inline clamps in WaterMarkRepository/MainViewModel so persisted
// values and render output stay unchanged. No Android types, no deps.
object WatermarkConfigRules {

    // Limits (single source; WaterMarkRepository const vals delegate here, editor sliders read them).
    const val MIN_TEXT_SIZE: Float = 1f
    const val MAX_TEXT_SIZE: Float = 100f
    const val DEFAULT_TEXT_SIZE: Float = 14f
    const val MAX_DEGREE: Float = 360f
    const val MAX_HORIZONTAL_GAP: Int = 500
    const val MAX_VERTICAL_GAP: Int = 500

    // Mode implied by a text update / an icon update.
    val MODE_ON_TEXT_UPDATE: WatermarkMode = WatermarkMode.Text
    val MODE_ON_ICON_UPDATE: WatermarkMode = WatermarkMode.Image

    // Read-path text size clamp: min only (legacy read coerceAtLeast(1f)). No upper clamp on storage today;
    // MAX_TEXT_SIZE bounds only the editor slider.
    fun clampTextSize(size: Float): Float = size.coerceAtLeast(MIN_TEXT_SIZE)

    // Alpha byte clamp to 0..255 (was alpha.coerceAtLeast(0).coerceAtMost(255)).
    fun clampAlphaByte(alpha: Int): Int = alpha.coerceAtLeast(0).coerceAtMost(255)

    // Alpha percent (0..100 slider) to 0..255 byte (was (alpha / 100 * 255).toInt(); float div + truncation).
    fun alphaPercentToByte(percent: Float): Int = (percent / 100 * 255).toInt()

    // Inverse of alphaPercentToByte: 0..255 byte to 0..100 percent for slider/field display (S4d-179).
    // Android baseline order (was inline `alpha.toFloat() / 255 * 100` in EditorScreen; Desktop used
    // `alpha * 100f / 255f`, value-equal up to the last float ULP). Display only — not persisted.
    fun alphaByteToPercent(alpha: Int): Float = alpha.toFloat() / 255 * 100

    // Gap clamps 0..MAX (was gap.coerceAtLeast(0).coerceAtMost(MAX_*_GAP)).
    fun clampHorizontalGap(gap: Int): Int = gap.coerceAtLeast(0).coerceAtMost(MAX_HORIZONTAL_GAP)

    fun clampVerticalGap(gap: Int): Int = gap.coerceAtLeast(0).coerceAtMost(MAX_VERTICAL_GAP)

    // Degree clamp 0f..MAX_DEGREE (was degree.coerceAtLeast(0f).coerceAtMost(MAX_DEGREE)).
    fun clampDegree(degree: Float): Float = degree.coerceAtLeast(0f).coerceAtMost(MAX_DEGREE)
}
