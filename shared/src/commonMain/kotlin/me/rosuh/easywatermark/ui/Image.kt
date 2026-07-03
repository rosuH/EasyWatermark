package me.rosuh.easywatermark.ui

import me.rosuh.easywatermark.data.model.MediaRef

/**
 * Platform-neutral gallery image identity for launch/gallery flows.
 *
 * The `uri` field is now a [MediaRef] instead of `android.net.Uri`, so this model can live in
 * `:shared/commonMain`. Android edges convert between `MediaRef` and `Uri` via
 * `utils.ktx.toMediaRef()` / `utils.ktx.toUri()`.
 */
data class Image(
    val id: Int,
    val uri: MediaRef,
    val name: String,
    val size: Long,
    val date: Long,
    val check: Boolean = false,
)
