@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import platform.Photos.PHCachingImageManager
import platform.Photos.PHImageContentModeAspectFit

/**
 * Photos-daemon prefetch for already-picked neighbor assets (ADR-0029 P4).
 *
 * Not an [me.rosuh.easywatermark.render.IosPreviewImageRepository] citizen.
 * Callers pass registry asset ids; production resolves [platform.Photos.PHAsset]
 * only at this edge. Never writes bitmaps into product caches.
 */
internal interface IosPhotoKitNeighborCache {
    fun start(assetIds: Collection<String>, targetPx: Int)
    fun stop(assetIds: Collection<String>, targetPx: Int)
    fun stopAll()
}

internal class PhotosDaemonNeighborCache : IosPhotoKitNeighborCache {
    private val manager = PHCachingImageManager()

    override fun start(assetIds: Collection<String>, targetPx: Int) {
        val assets = resolve(assetIds)
        if (assets.isEmpty() || targetPx <= 0) return
        manager.startCachingImagesForAssets(
            assets = assets,
            targetSize = IosPhotoKitImageSource.previewTargetSize(targetPx),
            contentMode = PHImageContentModeAspectFit,
            options = IosPhotoKitImageSource.previewRequestOptions(),
        )
    }

    override fun stop(assetIds: Collection<String>, targetPx: Int) {
        val assets = resolve(assetIds)
        if (assets.isEmpty() || targetPx <= 0) return
        manager.stopCachingImagesForAssets(
            assets = assets,
            targetSize = IosPhotoKitImageSource.previewTargetSize(targetPx),
            contentMode = PHImageContentModeAspectFit,
            options = IosPhotoKitImageSource.previewRequestOptions(),
        )
    }

    override fun stopAll() {
        manager.stopCachingImagesForAllAssets()
    }

    private fun resolve(assetIds: Collection<String>) =
        assetIds.mapNotNull { id ->
            id.takeIf { it.isNotBlank() }?.let(IosPhotoKitImageSource::resolveAsset)
        }
}
