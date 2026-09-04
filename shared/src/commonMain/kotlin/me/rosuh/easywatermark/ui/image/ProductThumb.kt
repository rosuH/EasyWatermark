package me.rosuh.easywatermark.ui.image

import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.ui.theme.ContentEditorTheme

/**
 * Coil `data` for product UI chrome thumbs (ADR-0028).
 *
 * Platform Fetchers map this to MediaStore (Android) or a file source
 * (Desktop/iOS JPEG/PNG → Coil/Skia size; iOS HEIC → ImageIO). Never bare full content open.
 * [maxEdgePx] is part of the memory-cache key so filmstrip + theme seed share entries when equal.
 */
data class ProductThumb(
    val ref: MediaRef,
    val maxEdgePx: Int = UI_THUMB_MAX_EDGE,
    val purpose: Purpose = Purpose.Chrome,
) {
    enum class Purpose {
        /** Gallery / filmstrip / save-sheet / icon preview. */
        Chrome,

        /** Content-theme MCU seed (same edge as [UI_THUMB_MAX_EDGE] for cache hits). */
        ThemeSeed,
    }

    companion object {
        /**
         * Shared UI thumb long-edge (px). Aligned with [ContentEditorTheme.SEED_MAX_EDGE]
         * so theme seed and filmstrip hit the same Coil memory entries.
         */
        const val UI_THUMB_MAX_EDGE: Int = ContentEditorTheme.SEED_MAX_EDGE
    }
}
