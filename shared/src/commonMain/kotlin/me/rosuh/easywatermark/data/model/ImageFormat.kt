package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral output image format (CMP plan D7). Now lives in `:shared/commonMain` — the
 * First domain type shared across platforms (compiles for Android + JVM/desktop). Replaces * `android.graphics.Bitmap.CompressFormat` in the model layer.
 *
 * [storageId] is the stable value persisted in DataStore, kept ordinal-compatible with the
 * historical `Bitmap.CompressFormat` ordinals (JPEG=0, PNG=1) so existing user preferences
 * round-trip without a migration (plan R6 — explicit id, not fragile cross-enum ordinal
 * equality). The platform encoder mapping lives at the encode edge in androidMain/`:app`.
 *
 * [fileExtension] is the default output-file extension (`jpg`/`png`), single-sourced here
 * so Android (`MainViewModel.trapOutputExtension`) and Desktop (`DesktopSaveDecision`) stop duplicating
 * the same mapping. It is the bare extension (not a MIME). Android Q+ MediaStore MIME is mapped
 * separately at the app edge via `ImageFormat.toMediaStoreMimeType()` (`image/jpeg` / `image/png`).
 */
enum class ImageFormat(val storageId: Int, val fileExtension: String) {
    JPEG(0, "jpg"),
    PNG(1, "png");

    companion object {
        fun fromStorageId(id: Int?): ImageFormat = entries.firstOrNull { it.storageId == id } ?: JPEG
    }
}
