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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android product UI Fetcher: MediaStore system thumbnail first, subsampled content decode fallback.
 * Never opens bare full content via Coil's default ContentUriFetcher (ADR-0028 / Q4=A).
 *
 * Lives in :shared androidMain — does not call :app BitmapUtils (module boundary).
 */
class ProductThumbFetcher(
    private val context: Context,
    private val data: ProductThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        if (data.ref.isEmpty()) return@withContext null
        val uri = Uri.parse(data.ref.value)
        val size = data.maxEdgePx.coerceIn(64, 512)
        val bitmap = loadThumbBitmap(context, uri, size) ?: return@withContext null
        // isSampled=false: ProductThumb maxEdge is the product-final UI size, not an
        // intermediate sample. Coil AsyncImage uses Size.ORIGINAL + Precision.INEXACT;
        // sampled cache entries fail size validation and never memory-hit → blank
        // flash on LazyRow recycle (filmstrip scroll away/back).
        ImageFetchResult(
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
            if (thumb != null && !thumb.isRecycled) return thumb
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
            if (thumb != null && !thumb.isRecycled) return thumb
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
        val sample = calculateInSampleSize(outW, outH, sizePx)
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

private fun calculateInSampleSize(outWidth: Int, outHeight: Int, maxEdgePx: Int): Int {
    var inSampleSize = 1
    val longest = maxOf(outWidth, outHeight)
    if (longest > maxEdgePx && maxEdgePx > 0) {
        var half = longest / 2
        while (half / inSampleSize >= maxEdgePx) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}
