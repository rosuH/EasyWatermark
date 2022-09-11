package me.rosuh.easywatermark.data.model

import androidx.annotation.DrawableRes
import androidx.annotation.Keep

@Keep
data class FuncTitleModel(
    var type: FuncType,
    var title: String,
    @DrawableRes var iconRes: Int
) {
    sealed class FuncType {
        object Text : FuncType()
        object Icon : FuncType()
        object Color : FuncType() {
            val tag = "Color"
        }
        object Alpha : FuncType()
        object Degree : FuncType()
        object TextStyle : FuncType()
        object Vertical : FuncType()
        object Horizon : FuncType()
        object TextSize : FuncType() {
            val tag = "TextSize"
        }

        object TileMode : FuncType() {
            val tag = "TileMode"
        }
    }
}
