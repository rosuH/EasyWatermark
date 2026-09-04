package me.rosuh.easywatermark.session

import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.Image

/**
 * Platform media-library seam (ADR-0017 follow-on).
 *
 * Android: MediaStore query / picker enrichment.
 * Desktop/iOS: typically unused (system pickers feed [MediaRef] paths/bytes directly).
 */
interface MediaLibraryPort {
    /** Full in-app gallery listing (Launch → gallery dialog). */
    suspend fun listImages(): List<Image>

    /**
 * Best-effort gallery rows for system-picker [MediaRef]s (e.g. MediaStore content URIs).
 * May return fewer rows than [refs] if metadata is unavailable; hosts still enter editor from refs.
     */
    suspend fun enrichPickerRefs(refs: List<MediaRef>): List<Image>
}
