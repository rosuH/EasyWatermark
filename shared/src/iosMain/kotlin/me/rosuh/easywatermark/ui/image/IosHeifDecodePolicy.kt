package me.rosuh.easywatermark.ui.image

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse

/**
 * Per-loader (and per-request) knobs for [IosHeifImageDecoder].
 *
 * Filmstrip / theme seed use [ProductUi]. Larger preview / export-adjacent HEIF can use
 * [Preview] or a custom instance — do not bake 128px into the decoder itself.
 */
internal data class IosHeifDecodePolicy(
    val name: String,
    /** When [ImageRequest.size] is ORIGINAL / undefined and no extra is set. */
    val fallbackMaxEdgePx: Int,
    val minEdgePx: Int,
    val maxEdgePx: Int,
    val sampled: SampledMode,
    /** ImageIO `kCGImageSourceShouldCache` — off for scroll thumbs (don't pin decode cache). */
    val imageIoShouldCache: Boolean,
) {
    enum class SampledMode {
        /** Always `isSampled=false` (product UI thumbs + LazyRow memory-cache contract). */
        Never,

        /** `isSampled` when output long-edge is smaller than source long-edge. */
        InferFromSource,
    }

    fun resolveMaxEdgePx(requestLongEdgePx: Int, extraMaxEdgePx: Int): Int {
        val raw = when {
            extraMaxEdgePx > 0 -> extraMaxEdgePx
            requestLongEdgePx > 0 -> requestLongEdgePx
            else -> fallbackMaxEdgePx
        }
        return raw.coerceIn(minEdgePx, maxEdgePx)
    }

    fun resolveIsSampled(sourceLongEdgePx: Int, outputLongEdgePx: Int): Boolean =
        when (sampled) {
            SampledMode.Never -> false
            SampledMode.InferFromSource ->
                sourceLongEdgePx > 0 && outputLongEdgePx > 0 && outputLongEdgePx < sourceLongEdgePx
        }

    companion object {
        val ProductUi = IosHeifDecodePolicy(
            name = "productUi",
            fallbackMaxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
            minEdgePx = 64,
            maxEdgePx = 512,
            sampled = SampledMode.Never,
            imageIoShouldCache = false,
        )

        val Preview = IosHeifDecodePolicy(
            name = "preview",
            fallbackMaxEdgePx = 720,
            minEdgePx = 64,
            maxEdgePx = 3840,
            sampled = SampledMode.InferFromSource,
            imageIoShouldCache = false,
        )
    }
}

/** Optional per-request long-edge override (pixels). `0` / unset → use [Options.size] + policy. */
internal val IosHeifMaxEdgePxExtra = Extras.Key(default = 0)

fun ImageRequest.Builder.iosHeifMaxEdgePx(px: Int) = apply {
    extras[IosHeifMaxEdgePxExtra] = px.coerceAtLeast(0)
}

internal fun Options.heifRequestLongEdgePx(): Int {
    val w = size.width.pxOrElse { 0 }
    val h = size.height.pxOrElse { 0 }
    return maxOf(w, h)
}

internal fun Options.heifExtraMaxEdgePx(): Int = getExtra(IosHeifMaxEdgePxExtra)

internal fun Size.longEdgePxOrZero(): Int {
    val w = width.pxOrElse { 0 }
    val h = height.pxOrElse { 0 }
    return maxOf(w, h)
}
