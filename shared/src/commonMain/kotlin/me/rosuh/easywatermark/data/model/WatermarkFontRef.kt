package me.rosuh.easywatermark.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Durable watermark font identity (ADR-0035). Display names live on [me.rosuh.easywatermark.font.FontEntry],
 * not here. [Unavailable] is a read-error sentinel and must never be written as a user selection.
 */
@Serializable
sealed interface WatermarkFontRef {
    @Serializable
    @SerialName("default")
    data object Default : WatermarkFontRef

    @Serializable
    @SerialName("system")
    data class System(val platform: String, val key: String) : WatermarkFontRef

    @Serializable
    @SerialName("imported")
    data class Imported(val sha256: String) : WatermarkFontRef

    @Serializable
    @SerialName("unavailable")
    data object Unavailable : WatermarkFontRef

    fun fingerprint(): String = when (this) {
        Default -> "default"
        is System -> "system:$platform:$key"
        is Imported -> "imported:$sha256"
        Unavailable -> "unavailable"
    }

    fun isSelectable(): Boolean = this !is Unavailable
}
