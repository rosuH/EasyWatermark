package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Path.Companion.toPath

/**
 * iOS product UI Fetcher: path → [SourceFetchResult] so Coil's decoder chain runs.
 *
 * JPEG/PNG: default [coil3.decode.SkiaImageDecoder] downsamples via request size.
 * HEIC/HEIF: [IosHeifImageDecoder] (ImageIO thumbnail — Skia cannot decode HEIF).
 */
class ProductThumbFetcher(
    private val data: ProductThumb,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (data.ref.isEmpty()) return null
        val path = data.ref.value
        if (path.isBlank() || path == "preview") return null
        me.rosuh.easywatermark.render.IosDecodePurposeProbe.record(
            me.rosuh.easywatermark.render.IosDecodePurposeProbe.Purpose.ProductThumbCoil,
        )
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
