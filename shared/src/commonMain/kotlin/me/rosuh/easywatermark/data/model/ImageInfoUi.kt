package me.rosuh.easywatermark.data.model

import androidx.compose.runtime.Immutable

/**
 * Immutable UI projection of [ImageInfo] for Editor filmstrip + preview composition.
 *
 * Export/session still own mutable [ImageInfo] (`var` width/jobState/result). Do **not** mark
 * [ImageInfo] `@Stable` while those public vars remain — this projection is the safe Compose path
 * (DIAGNOSIS 2026-08-08 P0).
 */
@Immutable
data class ImageInfoUi(
    val uri: MediaRef,
    val width: Int = 1,
    val height: Int = 1,
    val offsetX: Float = 0.5f,
    val offsetY: Float = 0.5f,
) {
    /** Offset/uri-only ImageInfo for Session applyOffset / select; dims preserved by Session CAS. */
    fun toImageInfo(): ImageInfo = ImageInfo(
        uri = uri,
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
    )
}

/** Drop mutable export fields before composition. */
fun ImageInfo.toUiProjection(): ImageInfoUi = ImageInfoUi(
    uri = uri,
    width = width,
    height = height,
    offsetX = offsetX,
    offsetY = offsetY,
)

/**
 * Stable Editor selection snapshot (display list + selected uri).
 * Honor immutability: never mutate [images] after construction.
 */
@Immutable
data class EditorSelectionUi(
    val images: List<ImageInfoUi>,
    val selectedUri: String? = null,
) {
    val selected: ImageInfoUi?
        get() = selectedUri?.let { key -> images.firstOrNull { it.uri.value == key } }
            ?: images.firstOrNull()
}

fun List<ImageInfo>.toEditorSelectionUi(selected: ImageInfo? = null): EditorSelectionUi =
    EditorSelectionUi(
        images = map { it.toUiProjection() },
        selectedUri = selected?.uri?.value ?: firstOrNull()?.uri?.value,
    )
