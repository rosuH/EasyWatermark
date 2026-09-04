package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Every gallery cell resolves through MediaProvider (`loadThumbnail`), a binder round trip that
 * serialises in the provider. Flinging composes dozens of cells per frame, and unbounded
 * `Dispatchers.IO` aimed all of them at that one queue, so the rows actually on screen waited
 * behind rows the user had already scrolled past. Bound the fan-out instead.
 */
private const val THUMB_FETCH_PARALLELISM: Int = 4

@OptIn(ExperimentalCoroutinesApi::class)
actual fun buildProductImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(ProductThumbKeyer())
            add(ProductThumbFetcher.Factory())
        }
        .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(THUMB_FETCH_PARALLELISM))
        .productThumbDefaults(context)
        .build()
