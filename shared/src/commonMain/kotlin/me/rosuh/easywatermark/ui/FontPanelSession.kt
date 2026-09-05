package me.rosuh.easywatermark.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.font.FontImportProgress
import me.rosuh.easywatermark.font.FontImportResult
import me.rosuh.easywatermark.font.FontResolution
import me.rosuh.easywatermark.font.FontSourceTab
import me.rosuh.easywatermark.font.FontStyleCapability
import me.rosuh.easywatermark.font.WatermarkFontAccess

/**
 * Host-owned font panel state. Not a ViewModel — each platform Host constructs one.
 */
class FontPanelSession(
    private val access: WatermarkFontAccess,
    private val scope: CoroutineScope,
    private val applySelection: suspend (
        generation: Int,
        ref: WatermarkFontRef,
        styles: Set<me.rosuh.easywatermark.data.model.TextTypeface>,
    ) -> Boolean,
    private val systemListRestricted: Boolean = false,
) {
    var state by mutableStateOf(
        FontPanelUiState(systemListRestricted = systemListRestricted),
    )
        private set

    var resolvedFamily by mutableStateOf<androidx.compose.ui.text.font.FontFamily?>(null)
        private set

    private var loadJob: Job? = null
    private var selectJob: Job? = null
    private var importJob: Job? = null
    private var importGeneration = 0
    private var selectGeneration = 0

    fun syncFromConfig(
        ref: WatermarkFontRef,
        sampleText: String,
        displayName: String? = null,
    ) {
        state = state.copy(
            selectedRef = ref,
            sampleText = sampleText,
            currentDisplayName = displayName ?: state.currentDisplayName.ifBlank {
                if (ref is WatermarkFontRef.Default) "" else state.currentDisplayName
            },
            unavailableMessage = if (ref is WatermarkFontRef.Unavailable) {
                state.unavailableMessage ?: "Current font is unavailable"
            } else {
                null
            },
        )
    }

    fun onOpen() {
        access.recoverOrphans()
        loadJob?.cancel()
        loadJob = scope.launch {
            state = state.copy(systemLoading = true, importedLoading = true)
            val system = runCatching { access.listSystemFonts() }
            val imported = runCatching { access.listImportedFonts() }
            state = state.copy(
                systemFonts = system.getOrDefault(state.systemFonts),
                importedFonts = imported.getOrDefault(state.importedFonts),
                systemLoading = false,
                importedLoading = false,
                systemError = system.exceptionOrNull()?.message,
                importedError = imported.exceptionOrNull()?.message,
            )
        }
        scope.launch { refreshCurrent(state.selectedRef) }
    }

    fun onEvent(event: FontPanelEvent) {
        when (event) {
            FontPanelEvent.Open -> onOpen()
            FontPanelEvent.Dismiss -> Unit
            FontPanelEvent.ImportFolder -> Unit
            FontPanelEvent.CancelImport -> cancelImport()
            FontPanelEvent.ToggleImportFailures -> {
                state = state.copy(showImportFailures = !state.showImportFailures)
            }
            is FontPanelEvent.Select -> select(event.ref)
            is FontPanelEvent.SourceTab -> {
                state = state.copy(sourceTab = event.tab)
            }
        }
    }

    fun select(ref: WatermarkFontRef) {
        if (!ref.isSelectable()) return
        val generation = ++selectGeneration
        state = state.copy(pendingRef = ref)
        selectJob?.cancel()
        selectJob = scope.launch {
            val resolution = access.resolve(ref)
            if (generation != selectGeneration) return@launch
            when (resolution) {
                is FontResolution.Failure -> {
                    state = state.copy(
                        pendingRef = null,
                        unavailableMessage = resolution.reason,
                    )
                }
                is FontResolution.Success -> {
                    val applied = applySelection(generation, ref, resolution.supportedStyles.styles)
                    if (generation != selectGeneration) return@launch
                    if (applied) {
                        resolvedFamily = resolution.family
                        val normalized = resolution.supportedStyles.normalize(
                            // UI hint only; actual normalize happens in DataStore.
                            me.rosuh.easywatermark.data.model.TextTypeface.Normal,
                        )
                        val hint = if (!resolution.supportedStyles.supports(
                                me.rosuh.easywatermark.data.model.TextTypeface.Bold,
                            ) ||
                            !resolution.supportedStyles.supports(
                                me.rosuh.easywatermark.data.model.TextTypeface.Italic,
                            )
                        ) {
                            "This font does not support every bold/italic style."
                        } else {
                            null
                        }
                        state = state.copy(
                            pendingRef = null,
                            selectedRef = ref,
                            currentDisplayName = resolution.displayName,
                            supportedStyles = resolution.supportedStyles,
                            unavailableMessage = null,
                            styleHint = hint,
                        )
                    } else {
                        state = state.copy(pendingRef = null)
                    }
                }
            }
        }
    }

    fun beginImport() {
        importGeneration += 1
        state = state.copy(importProgress = FontImportProgress.Running)
    }

    fun completeImport(result: FontImportResult) {
        scope.launch {
            val imported = runCatching { access.listImportedFonts() }.getOrDefault(state.importedFonts)
            state = state.copy(
                importedFonts = imported,
                importProgress = FontImportProgress.Done(result),
                sourceTab = FontSourceTab.Imported,
                importedLoading = false,
            )
        }
    }

    fun cancelImport() {
        importGeneration += 1
        importJob?.cancel()
        state = state.copy(importProgress = FontImportProgress.Idle)
    }

    fun importStillCurrent(generation: Int): Boolean = generation == importGeneration

    fun nextImportGeneration(): Int {
        importGeneration += 1
        return importGeneration
    }

    suspend fun refreshCurrent(ref: WatermarkFontRef) {
        val resolution = access.resolve(ref)
        when (resolution) {
            is FontResolution.Success -> {
                resolvedFamily = resolution.family
                state = state.copy(
                    currentDisplayName = resolution.displayName,
                    supportedStyles = resolution.supportedStyles,
                    unavailableMessage = null,
                )
            }
            is FontResolution.Failure -> {
                if (ref !is WatermarkFontRef.Default) {
                    state = state.copy(unavailableMessage = resolution.reason)
                }
            }
        }
    }

    fun applyConfigChange(change: WatermarkConfigChange) {
        if (change is WatermarkConfigChange.FontSelection) {
            state = state.copy(selectedRef = change.ref)
        }
    }
}
