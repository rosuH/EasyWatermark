package me.rosuh.easywatermark.render

import android.graphics.Bitmap

/**
 * Process-wide hook so [me.rosuh.easywatermark.MyApp.onTrimMemory] can drop cheap Watermarked
 * frames and neighbor Sources without recycling Bitmaps or evicting the focus Source.
 */
object AndroidPreviewWorkingSet {
    @Volatile
    var repository: PreviewImageRepository<Bitmap>? = null

    @Volatile
    var focusPath: String? = null

    fun attach(repo: PreviewImageRepository<Bitmap>) {
        repository = repo
    }

    fun detach(repo: PreviewImageRepository<Bitmap>) {
        if (repository === repo) {
            repository = null
        }
    }

    fun onTrimMemory() {
        val repo = repository ?: return
        repo.clearPurposeFromOwner(PreviewPurpose.Watermarked)
        val keep = focusPath?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: return
        repo.evictPurposeExceptFromOwner(PreviewPurpose.SourcePlaceholder, keep)
    }
}
