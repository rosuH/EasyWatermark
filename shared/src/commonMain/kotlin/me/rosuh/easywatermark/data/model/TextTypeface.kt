package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral text typeface style. Moved to `:shared/commonMain` and made self-contained:
 * It no longer extends the `java.io.Serializable`-based `SerializableSealClass` and no longer carries * Android UI methods (`applyStyle(TextView?)` was dead and was dropped).
 *
 * [serializeKey] is the stable int persisted in DataStore (`KEY_TEXT_TYPEFACE`): Normal=0, Italic=1,
 * Bold=2, BoldItalic=3 — unchanged from the legacy storage contract.
 *
 * [obtainSysTypeface] returns the same int, which is **numerically identical** to the Android
 * `android.graphics.Typeface` style constants (NORMAL=0, ITALIC=1, BOLD=2, BOLD_ITALIC=3). The return
 * type is a plain `Int` (no Android type), so it stays common; the Android `Typeface.create(...)` call
 * that consumes it lives at the Android edge (`utils/ktx/PainKtx.kt`).
 */
sealed class TextTypeface(private val key: Int) {

    fun serializeKey(): Int = key

    /** Int style key, 1:1 with android `Typeface` style constants (NORMAL/ITALIC/BOLD/BOLD_ITALIC). */
    fun obtainSysTypeface(): Int = key

    object Normal : TextTypeface(0)

    object Italic : TextTypeface(1)

    object Bold : TextTypeface(2)

    object BoldItalic : TextTypeface(3)

    companion object {
        fun obtainSealedClass(key: Int): TextTypeface {
            return when (key) {
                0 -> Normal
                1 -> Italic
                2 -> Bold
                3 -> BoldItalic
                else -> throw IllegalArgumentException("No such key for TextTypeface")
            }
        }
    }
}
