package me.rosuh.easywatermark.ui.image

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import me.rosuh.easywatermark.data.model.MediaRef

/**
 * Product UI thumb via Coil singleton + [ProductThumb] (ADR-0028).
 * Never pass bare content Uri — always [ProductThumb] / [MediaRef].
 */
@Composable
fun ProductAsyncImage(
    thumb: ProductThumb,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DefaultFilterQuality,
) {
    if (thumb.ref.isEmpty()) {
        Box(modifier = modifier)
        return
    }
    val context = LocalPlatformContext.current
    var retryTick by remember(thumb.ref.value, thumb.maxEdgePx) { mutableIntStateOf(0) }
    val request = remember(thumb.ref.value, thumb.maxEdgePx, thumb.purpose, retryTick) {
        val cacheKey = productThumbCacheKey(thumb.ref.value, thumb.maxEdgePx)
        ImageRequest.Builder(context)
            .data(thumb)
            // Explicit key + placeholder so LazyRow recycle shows the memory-cached
            // pixels on first frame instead of Empty→blank while the request restarts.
            .memoryCacheKey(cacheKey)
            .placeholderMemoryCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            // Match Fetcher max-edge; INEXACT so cache hits accept the decoded thumb.
            .size(thumb.maxEdgePx)
            .precision(Precision.INEXACT)
            .crossfade(false)
            .build()
    }
    // Use onState overload (no placeholder/error Painter params — separate overloads in Coil 3).
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = { state ->
            onState?.invoke(state)
            if (state is AsyncImagePainter.State.Error &&
                ProductThumbLoadPolicy.shouldRetry(retryTick)
            ) {
                retryTick += 1
            }
        },
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
    )
}

@Composable
fun ProductAsyncImage(
    ref: MediaRef,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdgePx: Int = ProductThumb.UI_THUMB_MAX_EDGE,
    contentScale: ContentScale = ContentScale.Crop,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
    ProductAsyncImage(
        thumb = ProductThumb(ref = ref, maxEdgePx = maxEdgePx),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = onState,
    )
}
