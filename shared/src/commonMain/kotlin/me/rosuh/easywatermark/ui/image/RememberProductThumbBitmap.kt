package me.rosuh.easywatermark.ui.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.data.model.MediaRef

/**
 * Load a [ProductThumb] into an [ImageBitmap] for non-AsyncImage consumers
 * (content-theme seed). Shares Coil memory cache with [ProductAsyncImage].
 */
@Composable
fun rememberProductThumbBitmap(
    ref: MediaRef?,
    maxEdgePx: Int = ProductThumb.UI_THUMB_MAX_EDGE,
    enabled: Boolean = true,
): ImageBitmap? {
    val context = LocalPlatformContext.current
    val refValue = ref?.value
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        refValue,
        maxEdgePx,
        enabled,
    ) {
        if (!enabled || ref == null || ref.isEmpty()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val cacheKey = productThumbCacheKey(ref.value, maxEdgePx)
                val request = ImageRequest.Builder(context)
                    .data(ProductThumb(ref, maxEdgePx, ProductThumb.Purpose.ThemeSeed))
                    .memoryCacheKey(cacheKey)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(maxEdgePx)
                    .scale(Scale.FILL)
                    .precision(Precision.INEXACT)
                    .build()
                val result = loader.execute(request)
                val image = (result as? SuccessResult)?.image ?: return@runCatching null
                image.toComposeImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** Platform: coil [Image] → Compose [ImageBitmap] for theme seed / rare non-Async consumers. */
internal expect fun Image.toComposeImageBitmap(): ImageBitmap
