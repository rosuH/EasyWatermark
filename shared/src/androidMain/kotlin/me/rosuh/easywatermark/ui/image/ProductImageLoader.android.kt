package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext

actual fun buildProductImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(ProductThumbKeyer())
            add(ProductThumbFetcher.Factory())
        }
        .productThumbDefaults()
        .build()
