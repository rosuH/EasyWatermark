package me.rosuh.easywatermark.session

import androidx.lifecycle.ViewModel

/**
 * Shared product session host (ADR-0017, Phase 0 scaffold).
 *
 * **Phase 0:** multiplatform [ViewModel] resolves on Android / Desktop / iOS — no product behavior yet.
 * Later phases add intents, [StateFlow] session state, and constructor-injected ports
 * (`MediaLibraryPort`, `ImagePipelinePort`, `ExportStorePort`) while Android UI remains green.
 *
 * Performance / Android skill gates for all follow-on work are recorded in ADR-0017 §4–§5
 * (lifecycle-aware collection, off-main heavy work, no export-path rewrite, golden/parity discipline).
 */
class WatermarkSessionViewModel : ViewModel()
