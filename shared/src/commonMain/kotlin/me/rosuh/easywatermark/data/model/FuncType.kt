package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral watermark control vocabulary (S4d-72). Extracted from the former nested control-type
 * enum inside the app's `FuncTitleModel` (which stays `:app` only because it carries Android
 * `@StringRes`/`@DrawableRes`). The type keys themselves are pure command identity, shared by the editor
 * UI rows and `MainViewModel`'s config dispatch. Names are unchanged so existing `FuncType.X` references
 * resolve identically.
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
     * they are not Bundle-storable and crash with
     * `IllegalArgumentException: Type of the key … is not supported`.
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
