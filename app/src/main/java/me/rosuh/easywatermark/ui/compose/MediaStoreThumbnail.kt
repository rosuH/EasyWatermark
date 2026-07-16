package me.rosuh.easywatermark.ui.compose

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
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gallery cell model that forces MediaStore thumbnail decode instead of opening the full
 * original still (Coil's default [coil3.fetch.ContentUriFetcher] uses openAssetFileDescriptor
 * on the full file — the main gallery scroll jank source on large libraries).
 */
data class MediaStoreThumbnail(
    val uri: Uri,
    val sizePx: Int,
)

class MediaStoreThumbnailKeyer : Keyer<MediaStoreThumbnail> {
    override fun key(data: MediaStoreThumbnail, options: Options): String =
        "ms_thumb;${data.uri};${data.sizePx}"
}

class MediaStoreThumbnailFetcher(
    private val context: Context,
    private val data: MediaStoreThumbnail,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val size = data.sizePx.coerceIn(96, 512)
        val bitmap = loadThumbBitmap(context, data.uri, size) ?: return@withContext null
        ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaStoreThumbnail> {
        override fun create(
            data: MediaStoreThumbnail,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = MediaStoreThumbnailFetcher(options.context, data)
    }
}

private fun loadThumbBitmap(context: Context, uri: Uri, sizePx: Int): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } else {
            @Suppress("DEPRECATION")
            val id = ContentUris.parseId(uri)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                opts,
            )
        }
    } catch (_: Exception) {
        null
    }
}

/** Shared gallery ImageLoader — MediaStore thumbs + modest memory cache. */
fun Context.galleryImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
        .components {
            add(MediaStoreThumbnailKeyer())
            add(MediaStoreThumbnailFetcher.Factory())
        }
        .build()
