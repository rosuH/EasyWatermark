package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.LaunchScreenUiState

/**
 * Platform-neutral product-session intents (ADR-0017).
 *
 * No Android `Uri`/Bitmap/resource ids — hosts map system callbacks at the edge.
 */
sealed class AppIntent {
    /** MediaStore / library query finished; open in-app gallery with [images]. */
    data class GalleryLoaded(val images: List<Image>) : AppIntent()

    data class ToggleGalleryItem(
        val image: Image,
        val index: Int,
        val checked: Boolean,
    ) : AppIntent()

    /** Gallery dialog closed. [selected] true applies checked items and enters editor. */
    data class DismissGallery(val selected: Boolean) : AppIntent()

    /** Clear gallery pick list only (legacy resetGalleryData). */
    data object ResetGalleryData : AppIntent()

    /**
 * Enter editor with an already-mapped selection (system picker, share-in, multi-select).
 * [gallerySnapshot] is optional filmstrip/gallery identity list for launch state.
     */
    data class EnterEditor(
        val selected: List<ImageInfo>,
        val gallerySnapshot: List<Image> = emptyList(),
        val waterMark: WaterMark = WaterMark.default,
    ) : AppIntent()

    data class SelectCurrent(val ref: MediaRef) : AppIntent()

    data object NavigateBack : AppIntent()

    /**
     * E0: open full-screen About and record [returnTo] (Launch or Editor) for back.
     * Other [LaunchScreenUiState] values are treated as Launch.
     */
    data class OpenAbout(
        val returnTo: LaunchScreenUiState = LaunchScreenUiState.Launch,
    ) : AppIntent()

    data object GoTemplate : AppIntent()
    data object GoEdit : AppIntent()
    data object GoEditDialog : AppIntent()
    data object ResetEditDialog : AppIntent()
    data class UseTemplate(val template: Template) : AppIntent()
    data object DatabaseError : AppIntent()

    /** Mirror repo waterMark into launch state (same as MainViewModel waterMarkFlow collect). */
    data class SyncWaterMark(val waterMark: WaterMark) : AppIntent()

    /** Mirror repo selected image into launch state. */
    data class SyncCurrentImage(val info: ImageInfo?) : AppIntent()

    /**
 * Start batch export for [images] (defaults to current session selection when empty list
 * Is passed from hosts that want repo list — hosts should pass explicit list).     */
    data class RequestExport(val images: List<ImageInfo>) : AppIntent()

    data object CancelExport : AppIntent()

    /** Typed watermark config edit (Phase 5 — shared editor path). */
    data class ApplyConfig(val change: WatermarkConfigChange) : AppIntent()

    data class ApplyTextStyle(val style: TextPaintStyle) : AppIntent()

    // Offset is not an AppIntent: production uses synchronous
    // WatermarkSessionViewModel.applyOffset (UI/Main) only — no async dual path.
}
