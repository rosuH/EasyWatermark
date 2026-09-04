package me.rosuh.easywatermark.data.model

import androidx.annotation.Keep

/**
 * Android editor edge token for config change callbacks.
 * S-i18n continue: no longer carries `@StringRes` / `@DrawableRes` — product chrome uses
 * Shared [FuncType.label] / [FuncType.iconPainter] from composeResources (ADR-0019). * Identity is [type]; optional [valueRange] for sliders that still construct a model.
 */
@Keep
data class FuncTitleModel(
    var type: FuncType,
    val valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
)
