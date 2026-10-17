package me.rosuh.easywatermark.render

import android.content.ComponentCallbacks2
import android.graphics.Bitmap

/**
 * Process-wide hook so [me.rosuh.easywatermark.MyApp.onTrimMemory] can drop reconstructable
 * preview frames without recycling Bitmaps.
 *
 * [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]: drop Watermarked + neighbor Sources; keep the
 * focus Source so a brief app-switch does not force three `openInputStream`s (ADR-0030).
 * [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] and worse: drop the whole working set — the
 * process is a kill candidate and every frame is reconstructable.
 * Below UI_HIDDEN: no-op (post-14 only UI_HIDDEN / BACKGROUND are meaningful).
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

    fun onTrimMemory(level: Int): String {
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return "none"
        val repo = repository ?: return "none"
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            repo.clearFromOwner()
            return "evict_all"
        }
        repo.clearPurposeFromOwner(PreviewPurpose.Watermarked)
        val keep = focusPath?.takeIf { it.isNotBlank() }?.let { setOf(it) }
        if (keep == null) {
            repo.clearPurposeFromOwner(PreviewPurpose.SourcePlaceholder)
            return "soft_no_focus"
        }
        repo.evictPurposeExceptFromOwner(PreviewPurpose.SourcePlaceholder, keep)
        return "soft_keep_focus"
    }
}
