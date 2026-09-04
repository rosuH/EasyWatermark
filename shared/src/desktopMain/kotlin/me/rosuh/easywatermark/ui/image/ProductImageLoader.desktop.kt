package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext

actual fun buildProductImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            // Before default Skia: same downsample, isSampled=false for LazyRow cache hits.
            add(DesktopProductSkiaDecoder.Factory())
            add(ProductThumbKeyer())
            add(ProductThumbFetcher.Factory())
        }
        .productThumbDefaults(context)
        .build()
