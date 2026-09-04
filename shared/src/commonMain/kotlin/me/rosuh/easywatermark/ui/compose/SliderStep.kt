package me.rosuh.easywatermark.ui.compose

import kotlin.math.roundToInt

/**
 * Pure step helper for form sliders (keyboard / wheel).
 *
 * - Arrow / wheel notch: [deltaUnits] = ±1 → one [step] (or 1f integer default)
 * - Shift+arrow: pass [deltaUnits] = ±10
 */
fun sliderStepValue(
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float? = null,
    deltaUnits: Int,
): Float {
    if (deltaUnits == 0) {
        return current.coerceIn(range.start, range.endInclusive)
    }
    val safeRange = if (range.endInclusive >= range.start) {
        range
    } else {
        range.endInclusive..range.start
    }
    val unit = when {
        step != null && step > 0f -> step
        else -> 1f
    }
    val next = current + unit * deltaUnits
    return snapSliderValue(next, safeRange, step)
}

/** Snap [raw] into [range] using the same rules as [SliderOption]. */
fun snapSliderValue(
    raw: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float? = null,
): Float {
    val safeRange = if (range.endInclusive >= range.start) {
        range
    } else {
        range.endInclusive..range.start
    }
    val clamped = raw.coerceIn(safeRange.start, safeRange.endInclusive)
    return if (step != null && step > 0f) {
        val start = safeRange.start
        val n = ((clamped - start) / step).roundToInt()
        (start + n * step).coerceIn(safeRange.start, safeRange.endInclusive)
    } else {
        clamped.roundToInt().toFloat()
    }
}
