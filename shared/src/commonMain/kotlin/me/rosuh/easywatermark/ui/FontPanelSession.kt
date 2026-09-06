package me.rosuh.easywatermark.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.font.FontEntry
import me.rosuh.easywatermark.font.FontImportFailure
import me.rosuh.easywatermark.font.FontImportProgress
import me.rosuh.easywatermark.font.FontImportResult
import me.rosuh.easywatermark.font.FontResolution
import me.rosuh.easywatermark.font.FontSourceTab
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

    var resolvedFamily by mutableStateOf<FontFamily?>(null)
        private set
    var resolvedRef by mutableStateOf<WatermarkFontRef?>(null)
        private set

    private var loadJob: Job? = null
    private var selectJob: Job? = null
    private var sampleJob: Job? = null
    private var importJob: Job? = null
    private var importGeneration = 0
    private var selectGeneration = 0
    private val sampleCache = LinkedHashMap<String, FontFamily>()

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
        state = state.copy(searchQuery = "")
        loadJob = scope.launch {
            state = state.copy(
                systemLoading = true,
                importedLoading = true,
            )
            val system = runCatching { access.listSystemFonts() }
            val imported = runCatching { access.listImportedFonts() }
            val systemFonts = system.getOrDefault(state.systemFonts)
            val importedFonts = imported.getOrDefault(state.importedFonts)
            state = state.copy(
                systemFonts = systemFonts,
                importedFonts = importedFonts,
                systemLoading = false,
                importedLoading = false,
                systemError = system.exceptionOrNull()?.message,
                importedError = imported.exceptionOrNull()?.message,
            )
            ensureSamples(visibleEntries().take(SAMPLE_CACHE_MAX))
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
                ensureSamples(visibleEntries(event.tab).take(SAMPLE_CACHE_MAX))
            }
            is FontPanelEvent.VisibleEntries -> {
                if (event.entries.isNotEmpty()) ensureSamples(event.entries)
            }
            is FontPanelEvent.SearchQuery -> {
                state = state.copy(searchQuery = event.query)
                val visible = visibleEntries()
                if (visible.isNotEmpty()) ensureSamples(visible.take(SAMPLE_CACHE_MAX))
            }
            FontPanelEvent.ClearSearch -> {
                state = state.copy(searchQuery = "")
                val visible = visibleEntries()
                if (visible.isNotEmpty()) ensureSamples(visible.take(SAMPLE_CACHE_MAX))
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
                        resolvedRef = ref
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

    /** Allocates one generation and marks the panel Running. */
    fun beginImport(): Int {
        val generation = ++importGeneration
        state = state.copy(importProgress = FontImportProgress.Running)
        return generation
    }

    suspend fun completeImport(generation: Int, result: FontImportResult) {
        val imported = runCatching { access.listImportedFonts() }.getOrDefault(state.importedFonts)
        if (importStillCurrent(generation)) {
            state = state.copy(
                importedFonts = imported,
                importProgress = FontImportProgress.Done(result),
                sourceTab = FontSourceTab.Imported,
                importedLoading = false,
            )
            ensureSamples(visibleEntries(FontSourceTab.Imported).take(SAMPLE_CACHE_MAX))
            return
        }
        // Stale job after cancel: refresh the catalog unless a newer import is running.
        if (state.importProgress is FontImportProgress.Running) return
        state = state.copy(
            importedFonts = imported,
            importedLoading = false,
            sourceTab = if (imported.isNotEmpty()) FontSourceTab.Imported else state.sourceTab,
        )
        ensureSamples(
            if (state.sourceTab == FontSourceTab.Imported) {
                imported.take(SAMPLE_CACHE_MAX)
            } else {
                visibleEntries().take(SAMPLE_CACHE_MAX)
            },
        )
    }

    fun cancelImport() {
        val generation = ++importGeneration
        importJob?.cancel()
        state = state.copy(importProgress = FontImportProgress.Idle)
        scope.launch {
            val imported = runCatching { access.listImportedFonts() }.getOrDefault(state.importedFonts)
            if (generation != importGeneration) return@launch
            if (state.importProgress is FontImportProgress.Running) return@launch
            state = state.copy(
                importedFonts = imported,
                importedLoading = false,
                sourceTab = if (imported.isNotEmpty()) FontSourceTab.Imported else state.sourceTab,
            )
            ensureSamples(
                if (state.sourceTab == FontSourceTab.Imported) {
                    imported.take(SAMPLE_CACHE_MAX)
                } else {
                    visibleEntries().take(SAMPLE_CACHE_MAX)
                },
            )
        }
    }

    fun importStillCurrent(generation: Int): Boolean = generation == importGeneration

    suspend fun failImport(generation: Int, reason: String) {
        completeImport(
            generation,
            FontImportResult(
                added = 0,
                duplicates = 0,
                failed = listOf(FontImportFailure("import", reason)),
            ),
        )
    }

    /**
     * Host import boundary: convert operational failures into a terminal panel result
     * so progress cannot stay Running. Coroutine cancellation is not swallowed.
     */
    suspend fun runImport(
        generation: Int,
        block: suspend () -> FontImportResult,
    ) {
        try {
            completeImport(generation, block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failImport(
                generation,
                e.message?.takeIf { it.isNotBlank() } ?: "Import failed",
            )
        }
    }

    fun visibleEntries(tab: FontSourceTab = state.sourceTab): List<FontEntry> {
        val source = when (tab) {
            FontSourceTab.System -> state.systemFonts
            FontSourceTab.Imported -> state.importedFonts
        }
        return FontNameQuery.filter(source, state.searchQuery)
    }

    fun ensureSamples(entries: List<FontEntry>) {
        sampleJob?.cancel()
        sampleJob = scope.launch {
            val next = LinkedHashMap(sampleCache)
            for (entry in entries.filter { it.available }) {
                val key = entry.ref.fingerprint()
                val cached = next.remove(key)
                if (cached != null) {
                    next[key] = cached
                    continue
                }
                when (val resolution = access.resolve(entry.ref)) {
                    is FontResolution.Success -> {
                        while (next.size >= SAMPLE_CACHE_MAX) {
                            val oldest = next.keys.first()
                            next.remove(oldest)
                        }
                        next[key] = resolution.family
                    }
                    is FontResolution.Failure -> Unit
                }
            }
            sampleCache.clear()
            sampleCache.putAll(next)
            state = state.copy(sampleFamilies = next.toMap())
        }
    }

    suspend fun refreshCurrent(ref: WatermarkFontRef) {
        val resolution = access.resolve(ref)
        when (resolution) {
            is FontResolution.Success -> {
                resolvedFamily = resolution.family
                resolvedRef = ref
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

    fun bindResolved(ref: WatermarkFontRef, family: FontFamily) {
        resolvedFamily = family
        resolvedRef = ref
    }

    fun applyConfigChange(change: WatermarkConfigChange) {
        if (change is WatermarkConfigChange.FontSelection) {
            state = state.copy(selectedRef = change.ref)
        }
    }

    companion object {
        const val SAMPLE_CACHE_MAX: Int = 24
    }
}
