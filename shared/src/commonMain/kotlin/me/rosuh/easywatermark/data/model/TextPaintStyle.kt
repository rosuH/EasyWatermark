package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral text paint style. Moved to `:shared/commonMain` and made self-contained:
 * No longer extends the `java.io.Serializable`-based `SerializableSealClass`, and the Android-typed * `obtainSysStyle(): android.graphics.Paint.Style` was moved to an Android edge mapper
 * (`utils/ktx/TextStyleExt.kt`); `applyStyle(TextView?)` was dead and was dropped.
 *
 * [serializeKey] is the stable int persisted in DataStore (`KEY_TEXT_STYLE`): Fill=0, Stroke=1 —
 * unchanged from the legacy storage contract.
 */
sealed class TextPaintStyle(private val key: Int) {

    fun serializeKey(): Int = key

    object Fill : TextPaintStyle(0)

    object Stroke : TextPaintStyle(1)

    companion object {
        fun obtainSealedClass(key: Int): TextPaintStyle {
            return when (key) {
                0 -> Fill
                1 -> Stroke
                else -> throw IllegalArgumentException("No such key for TextPaintStyle")
            }
        }
    }
}
