package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy

/**
 * Build the process singleton product [ImageLoader] (ADR-0028).
 * Registers [ProductThumb] Keyer + platform Fetcher; memory on, disk off.
 */
expect fun buildProductImageLoader(context: PlatformContext): ImageLoader

/** Install [buildProductImageLoader] as Coil's process singleton (safe if already set). */
fun installProductImageLoaderFactory() {
    SingletonImageLoader.setSafe { context -> buildProductImageLoader(context) }
}

/**
 * Disk is off (ADR-0028), so every eviction costs a full re-decode. The in-app gallery is the
 * worst case: a screen of cells is ~1 MB, and flinging back up through Coil's default budget
 * re-decoded rows the user had just seen.
 */
internal const val PRODUCT_THUMB_MEMORY_CACHE_PERCENT: Double = 0.30

internal fun ImageLoader.Builder.productThumbDefaults(
    context: PlatformContext,
): ImageLoader.Builder =
    memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, PRODUCT_THUMB_MEMORY_CACHE_PERCENT)
                .build()
        }
