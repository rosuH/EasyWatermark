package me.rosuh.easywatermark.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes

@Keep
data class FuncTitleModel(
    var type: FuncType,
    @StringRes var title: Int,
    @DrawableRes var iconRes: Int,
    val valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
) {
    sealed class FuncType {
        object Text : FuncType()
        object Icon : FuncType()
        object Color : FuncType()

        object Alpha : FuncType()
        object Degree : FuncType()
        object TextTypeFace : FuncType()
        object Vertical : FuncType()
        object Horizon : FuncType()
        object TextSize : FuncType()

        object TileMode : FuncType()
    }
}
