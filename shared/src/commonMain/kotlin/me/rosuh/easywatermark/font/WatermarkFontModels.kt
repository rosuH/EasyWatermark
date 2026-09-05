package me.rosuh.easywatermark.font

import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkFontRef

data class FontEntry(
    val ref: WatermarkFontRef,
    val displayName: String,
    val available: Boolean = true,
)

sealed class FontResolution {
    data class Success(
        val family: FontFamily,
        val supportedStyles: FontStyleCapability,
        val displayName: String,
    ) : FontResolution()

    data class Failure(
        val reason: String,
        val ref: WatermarkFontRef,
    ) : FontResolution()
}

data class FontStyleCapability(
    val styles: Set<TextTypeface>,
) {
    fun supports(typeface: TextTypeface): Boolean = typeface in styles

    fun normalize(typeface: TextTypeface): TextTypeface {
        if (supports(typeface)) return typeface
        if (supports(TextTypeface.Normal)) return TextTypeface.Normal
        return styles.firstOrNull() ?: TextTypeface.Normal
    }

    companion object {
        val All = FontStyleCapability(
            setOf(
                TextTypeface.Normal,
                TextTypeface.Bold,
                TextTypeface.Italic,
                TextTypeface.BoldItalic,
            ),
        )
        val NormalOnly = FontStyleCapability(setOf(TextTypeface.Normal))

        fun ofFace(bold: Boolean, italic: Boolean): FontStyleCapability {
            val style = when {
                bold && italic -> TextTypeface.BoldItalic
                bold -> TextTypeface.Bold
                italic -> TextTypeface.Italic
                else -> TextTypeface.Normal
            }
            return FontStyleCapability(setOf(style))
        }
    }
}

enum class FontSourceTab {
    System,
    Imported,
}

data class FontImportLimits(
    val maxFileBytes: Long = 16L * 1024L * 1024L,
    val maxTotalBytes: Long = 64L * 1024L * 1024L,
    val maxCandidates: Int = 200,
    val maxStored: Int = 200,
) {
    companion object {
        val Default = FontImportLimits()
    }
}

data class FontImportResult(
    val added: Int,
    val duplicates: Int,
    val failed: List<FontImportFailure>,
    val truncated: Boolean = false,
    val truncateReason: String? = null,
    val cancelled: Boolean = false,
) {
    val hasWork: Boolean get() = added > 0 || duplicates > 0 || failed.isNotEmpty() || truncated
}

data class FontImportFailure(
    val fileName: String,
    val reason: String,
)

sealed class FontImportProgress {
    data object Idle : FontImportProgress()
    data object Running : FontImportProgress()
    data class Done(val result: FontImportResult) : FontImportProgress()
}

interface WatermarkFontAccess {
    suspend fun listSystemFonts(): List<FontEntry>
    suspend fun listImportedFonts(): List<FontEntry>
    suspend fun resolve(ref: WatermarkFontRef): FontResolution
    fun recoverOrphans()
}

fun FontResolution.familyOrNull(): FontFamily? =
    (this as? FontResolution.Success)?.family
