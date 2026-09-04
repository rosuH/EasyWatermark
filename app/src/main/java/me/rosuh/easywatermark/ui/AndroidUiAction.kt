package me.rosuh.easywatermark.ui

import android.content.ContentResolver
import android.net.Uri
import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Android UI edge actions (ADR-0017 Phase 5).
 *
 * Carries [Uri]/[ContentResolver] and is mapped once in
 * [MainViewModel.process] → shared [me.rosuh.easywatermark.session.AppIntent].
 * F2: watermark config uses typed [me.rosuh.easywatermark.data.model.WatermarkConfigChange]
 * via [MainViewModel.applyConfig] — no raw WaterMarkChange Action.
 */
sealed class Action {
    data class DialogDismiss(val isSelected: Boolean) : Action()

    data class GalleryImageSelected(val image: Image, val index: Int, val isCheck: Boolean) : Action()

    data class SystemPickerImageSelected(
        val uriList: List<Uri>,
    ) : Action()

    data class LoadImages(val resolver: ContentResolver) : Action()

    data class EditorImageSelected(val image: ImageInfo) : Action()
}
