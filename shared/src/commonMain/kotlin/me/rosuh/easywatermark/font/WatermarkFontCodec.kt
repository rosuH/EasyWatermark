package me.rosuh.easywatermark.font

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rosuh.easywatermark.data.model.WatermarkFontRef

/**
 * Versioned JSON encoding for the DataStore font key. Missing key → [WatermarkFontRef.Default].
 * Unknown/corrupt payload → [WatermarkFontRef.Unavailable] without throwing in the Flow.
 */
object WatermarkFontCodec {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun encode(ref: WatermarkFontRef): String {
        require(ref.isSelectable()) { "Unavailable cannot be persisted as a selection" }
        return json.encodeToString(StoredFontRef(SCHEMA_VERSION, ref))
    }

    fun decode(raw: String?): WatermarkFontRef {
        if (raw.isNullOrBlank()) return WatermarkFontRef.Default
        return try {
            val stored = json.decodeFromString<StoredFontRef>(raw)
            when (val ref = stored.ref) {
                is WatermarkFontRef.Imported ->
                    if (SHA256_HEX.matches(ref.sha256)) ref else WatermarkFontRef.Unavailable
                is WatermarkFontRef.System ->
                    if (ref.platform.isNotBlank() && ref.key.isNotBlank()) {
                        ref
                    } else {
                        WatermarkFontRef.Unavailable
                    }
                WatermarkFontRef.Default -> WatermarkFontRef.Default
                WatermarkFontRef.Unavailable -> WatermarkFontRef.Unavailable
            }
        } catch (_: Exception) {
            WatermarkFontRef.Unavailable
        }
    }

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
}

@Serializable
internal data class StoredFontRef(
    val v: Int = WatermarkFontCodec.SCHEMA_VERSION,
    val ref: WatermarkFontRef,
)
