package me.rosuh.easywatermark.render

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/** Editor-preview chrome after ADR-0033: wait slot or atomic two-layer live paint. */
enum class OverlayPreviewChrome {
    EditorEmpty,
    WaitThumb,
    WaitEmpty,
    LiveLayers,
}

/**
 * Pure gate for the editor main preview (ADR-0033).
 *
 * [OverlayPreviewChrome.LiveLayers] only when a preview-resolution photo and a matching
 * overlay cell can appear together. Text cells must match the displayed photo width;
 * icon cells (`textSize/14`) may be reused across widths. A late cell never publishes
 * a bare Source / Library photo — wait chrome stays thumb or empty.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
object OverlayPreviewPolicy {

    fun decide(
        selectedPath: String?,
        photoPath: String?,
        photoWidth: Int?,
        cellReadyForWidth: Int?,
        hasThumb: Boolean,
        isTextMode: Boolean,
    ): OverlayPreviewChrome {
        if (selectedPath.isNullOrBlank()) return OverlayPreviewChrome.EditorEmpty
        if (canPublishLivePhoto(selectedPath, photoPath, photoWidth, cellReadyForWidth, isTextMode)) {
            return OverlayPreviewChrome.LiveLayers
        }
        return if (hasThumb) OverlayPreviewChrome.WaitThumb else OverlayPreviewChrome.WaitEmpty
    }

    fun canPublishLivePhoto(
        selectedPath: String?,
        photoPath: String?,
        photoWidth: Int?,
        cellReadyForWidth: Int?,
        isTextMode: Boolean,
    ): Boolean {
        if (selectedPath.isNullOrBlank() || photoPath.isNullOrBlank()) return false
        if (photoPath != selectedPath) return false
        val width = photoWidth ?: return false
        if (width <= 0) return false
        return cellMatchesDisplayedWidth(isTextMode, width, cellReadyForWidth)
    }

    fun cellMatchesDisplayedWidth(
        isTextMode: Boolean,
        displayedWidth: Int,
        cellReadyForWidth: Int?,
    ): Boolean {
        val ready = cellReadyForWidth ?: return false
        if (ready <= 0 || displayedWidth <= 0) return false
        return if (isTextMode) ready == displayedWidth else true
    }
}
