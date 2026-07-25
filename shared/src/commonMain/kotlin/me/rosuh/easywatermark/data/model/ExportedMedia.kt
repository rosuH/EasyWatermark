package me.rosuh.easywatermark.data.model

/**
 * Immutable product facts for one successful export item (Stage D / D1).
 *
 * Pure commonMain — no Android/iOS types. Adapters and Session must not rely solely on
 * mutating [ImageInfo] width/height for success identity; those fields may still be updated
 * for legacy UI, but [ExportedMedia] is the complete success payload from [me.rosuh.easywatermark.session.ExportPipelinePort].
 */
data class ExportedMedia(
    val ref: MediaRef,
    val width: Int,
    val height: Int,
    val format: ImageFormat,
    val byteCount: Long,
)
