package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
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

internal fun ImageLoader.Builder.productThumbDefaults(): ImageLoader.Builder =
    memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
