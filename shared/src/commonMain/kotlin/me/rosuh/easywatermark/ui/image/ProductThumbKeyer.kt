package me.rosuh.easywatermark.ui.image

import coil3.key.Keyer
import coil3.request.Options

/** Memory-cache key: ref + maxEdge (purpose omitted while edges match). */
class ProductThumbKeyer : Keyer<ProductThumb> {
    override fun key(data: ProductThumb, options: Options): String =
        productThumbCacheKey(data.ref.value, data.maxEdgePx)
}

fun productThumbCacheKey(refValue: String, maxEdgePx: Int): String =
    "ewm_thumb;${refValue};${maxEdgePx}"
