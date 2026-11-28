package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.repo.IosIconPersistence

/**
 * iOS thin wrapper over [WatermarkIconCache]. Decode stays ImageIO.
 * J5: internal raster helper — not part of the Swift product API surface.
 */
internal object IosWatermarkIconCache {
    private val cache = WatermarkIconCache<ImageBitmap>()

    fun decoded(ref: MediaRef, maxEdgePx: Int): ImageBitmap {
        val resolved = IosIconPersistence.resolveOwnedPath(ref.value) ?: ref.value
        if (resolved.isBlank() || !IosIconPersistence.canReadIcon(ref)) {
            error("IosIconPersistence: could not read icon bytes at '${ref.value}'")
        }
        val keyRef = MediaRef(resolved)
        return cache.decoded(keyRef, maxEdgePx) {
            runCatching {
                IosImageIODecoder.decodeThumbnail(
                    sourcePath = resolved,
                    maxEdgePx = maxEdgePx,
                    allowSubsample = true,
                )
            }.getOrElse {
                val bytes = IosIconPersistence.readIconBytes(ref)
                IosImageDecoder.decodeThumbnail(bytes, maxEdgePx = maxEdgePx)
            }
        }
    }

    fun invalidate() {
        cache.invalidate()
    }

    fun decodeCountForTests(): Int = cache.decodeCountForTests()

    fun resetForTests() {
        cache.resetForTests()
    }
}
