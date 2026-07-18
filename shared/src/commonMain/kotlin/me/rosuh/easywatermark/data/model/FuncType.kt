package me.rosuh.easywatermark.data.model

/**
 * Watermark editor control identity (text, color, alpha, tile mode, …).
 *
 * Used for option chrome (label/icon/key). Prefer typed [WatermarkConfigChange] for value transport.
 */
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

    /**
 * Bundle-safe identity for Compose Lazy keys / saveable state.
 * Do **not** use [FuncType] object instances as Lazy item keys on Android —
 * They are not Bundle-storable and crash with * `IllegalArgumentException: Type of the key … is not supported`.
     */
    fun stableKey(): String = when (this) {
        Text -> "Text"
        Icon -> "Icon"
        Color -> "Color"
        Alpha -> "Alpha"
        Degree -> "Degree"
        TextTypeFace -> "TextTypeFace"
        Vertical -> "Vertical"
        Horizon -> "Horizon"
        TextSize -> "TextSize"
        TileMode -> "TileMode"
    }
}
