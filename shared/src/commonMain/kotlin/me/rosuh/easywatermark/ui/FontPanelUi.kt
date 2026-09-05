package me.rosuh.easywatermark.ui

import androidx.compose.ui.text.font.FontFamily
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.font.FontEntry
import me.rosuh.easywatermark.font.FontImportProgress
import me.rosuh.easywatermark.font.FontSourceTab
import me.rosuh.easywatermark.font.FontStyleCapability

data class FontPanelUiState(
    val systemFonts: List<FontEntry> = emptyList(),
    val importedFonts: List<FontEntry> = emptyList(),
    val systemLoading: Boolean = false,
    val importedLoading: Boolean = false,
    val systemError: String? = null,
    val importedError: String? = null,
    val sourceTab: FontSourceTab = FontSourceTab.System,
    val importProgress: FontImportProgress = FontImportProgress.Idle,
    val selectedRef: WatermarkFontRef = WatermarkFontRef.Default,
    val pendingRef: WatermarkFontRef? = null,
    val currentDisplayName: String = "",
    val sampleText: String = "",
    val systemListRestricted: Boolean = false,
    val unavailableMessage: String? = null,
    val styleHint: String? = null,
    val supportedStyles: FontStyleCapability = FontStyleCapability.All,
    val showImportFailures: Boolean = false,
    val sampleFamilies: Map<String, FontFamily> = emptyMap(),
)

sealed class FontPanelEvent {
    data object Open : FontPanelEvent()
    data object Dismiss : FontPanelEvent()
    data object ImportFolder : FontPanelEvent()
    data object CancelImport : FontPanelEvent()
    data object ToggleImportFailures : FontPanelEvent()
    data class Select(val ref: WatermarkFontRef) : FontPanelEvent()
    data class SourceTab(val tab: FontSourceTab) : FontPanelEvent()
}

fun FontStyleCapability.toTypefaceSet(): Set<TextTypeface> = styles
