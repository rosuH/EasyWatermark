package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Path.Companion.toPath
import java.io.File

/**
 * Desktop product UI Fetcher: path → [SourceFetchResult] so Coil/Skia downsamples via request size
 * (A/B: ~4ms vs ~15ms ImageIO+repack on 3000×2000 JPEG → 128).
 *
 * EXIF is already baked by skiko `makeFromEncoded` — do not rotate again.
 */
class ProductThumbFetcher(
    private val data: ProductThumb,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (data.ref.isEmpty()) return null
        val path = data.ref.value
        val file = File(path)
        if (!file.isFile) return null
        return SourceFetchResult(
            source = ImageSource(file = path.toPath(), fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ProductThumb> {
        override fun create(
            data: ProductThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ProductThumbFetcher(data, options)
    }
}
