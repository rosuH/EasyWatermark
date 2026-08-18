package me.rosuh.easywatermark.ui.image

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options

/**
 * Android product UI Fetcher: MediaStore system thumbnail first, subsampled content decode fallback.
 * Never opens bare full content via Coil's default ContentUriFetcher (ADR-0028 / Q4=A).
 *
 * `loadThumbnail(Size(n, n))` fits the whole image into a square. Extreme aspects
 * collapse the short edge; those slivers are discarded and re-decoded so Crop cells
 * keep a usable short edge ([ProductThumbFit]).
 *
 * Lives in :shared androidMain — does not call :app BitmapUtils (module boundary).
 */
class ProductThumbFetcher(
    private val context: Context,
    private val data: ProductThumb,
) : Fetcher {

    // Runs on the loader's fetcherCoroutineContext, which is a parallelism-bounded view of
    // Dispatchers.IO — do not re-dispatch to plain IO here or the bound is bypassed.
    override suspend fun fetch(): FetchResult? {
        if (data.ref.isEmpty()) return null
        val uri = Uri.parse(data.ref.value)
        val size = data.maxEdgePx.coerceIn(64, 512)
        val bitmap = loadThumbBitmap(context, uri, size) ?: return null
        // isSampled=false: ProductThumb maxEdge is the product-final UI size, not an
        // intermediate sample. Coil request is size(maxEdge)+FILL+INEXACT; sampled
        // cache entries fail size validation and never memory-hit → blank flash on
        // LazyRow recycle (filmstrip scroll away/back).
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ProductThumb> {
        override fun create(
            data: ProductThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ProductThumbFetcher(options.context, data)
    }
}

private fun loadThumbBitmap(context: Context, uri: Uri, sizePx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val thumb = context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            if (!thumb.isRecycled) {
                if (ProductThumbFit.isUsableSquareThumb(thumb.width, thumb.height, sizePx)) {
                    return thumb
                }
                thumb.recycle()
            }
        } catch (_: Exception) {
            // fall through
        }
    } else {
        try {
            @Suppress("DEPRECATION")
            val id = ContentUris.parseId(uri)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            @Suppress("DEPRECATION")
            val thumb = MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                opts,
            )
            if (thumb != null && !thumb.isRecycled) {
                if (ProductThumbFit.isUsableSquareThumb(thumb.width, thumb.height, sizePx)) {
                    return thumb
                }
                thumb.recycle()
            }
        } catch (_: Exception) {
            // fall through
        }
    }
    // Subsampled full decode for app-private / non-MediaStore refs (last chance).
    return try {
        var outW = 0
        var outH = 0
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            outW = bounds.outWidth
            outH = bounds.outHeight
        }
        val sample = ProductThumbFit.inSampleSizeForCrop(outW, outH, sizePx)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (_: Exception) {
        null
    }
}
