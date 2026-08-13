package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.SkiaImageDecoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options

/**
 * Coil Skia decode (the measured A/B-B path) with [DecodeResult.isSampled] forced false.
 *
 * Filmstrip [ProductAsyncImage] must memory-hit on LazyRow recycle. Default
 * [SkiaImageDecoder] sets `isSampled=true` whenever it downsamples; that used to
 * fail cache size validation.
 *
 * Do **not** re-apply JPEG EXIF here. skiko `Image.makeFromEncoded` already bakes
 * orientation (`SkiaExifDecodeProbeTest`). A second bake double-rotates camera JPEGs.
 */
internal class DesktopProductSkiaDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val inner = SkiaImageDecoder(result.source, options).decode()
        return DecodeResult(image = inner.image, isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = DesktopProductSkiaDecoder(result, options)
    }
}
