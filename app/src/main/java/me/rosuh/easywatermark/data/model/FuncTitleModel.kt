package me.rosuh.easywatermark.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes

// S4d-72: `type` now uses the platform-neutral `FuncType` (commonMain). `FuncTitleModel` stays `:app`
// because it carries Android `@StringRes`/`@DrawableRes` ids; the former nested `FuncType` sealed class
// was moved to `shared/commonMain` (same `FuncType.*` names).
@Keep
data class FuncTitleModel(
    var type: FuncType,
    @param:StringRes var title: Int,
    @param:DrawableRes var iconRes: Int,
    val valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
)
