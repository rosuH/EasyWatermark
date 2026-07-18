package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.ui.Image

/**
 * Platform-neutral product-session intents (ADR-0017 / Phase 1).
 *
 * No [android.net.Uri], ContentResolver, Bitmap, or Android resource-id models.
 * Android maps legacy `Action` → these intents at the edge.
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
     * is passed from hosts that want repo list — hosts should pass explicit list).
     */
    data class RequestExport(val images: List<ImageInfo>) : AppIntent()

    data object CancelExport : AppIntent()

    /** Typed watermark config edit (Phase 5 — shared editor path). */
    data class ApplyConfig(val change: WatermarkConfigChange) : AppIntent()

    data class ApplyTextStyle(val style: TextPaintStyle) : AppIntent()

    // Offset is not an AppIntent: production uses synchronous
    // WatermarkSessionViewModel.applyOffset (UI/Main) only — no async dual path.
}
