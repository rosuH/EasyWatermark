package me.rosuh.easywatermark.data.model

/**
 * Platform-neutral output image format (CMP plan D7 — replaces `android.graphics.Bitmap.CompressFormat`
 * in the domain/model layer so the model can move to `commonMain`).
 *
 * [storageId] is the stable value persisted in DataStore. It is kept **ordinal-compatible with the
 * historical `Bitmap.CompressFormat` ordinals** (JPEG=0, PNG=1) so existing user preferences
 * round-trip without a migration (plan R6 — uses an explicit id, not fragile cross-enum ordinal
 * equality). Map to the platform encoder at the encode edge via `ImageFormat.toCompressFormat()`
 * (androidMain).
 */
enum class ImageFormat(val storageId: Int) {
    JPEG(0),
    PNG(1);

    companion object {
        fun fromStorageId(id: Int?): ImageFormat = entries.firstOrNull { it.storageId == id } ?: JPEG
    }
}
