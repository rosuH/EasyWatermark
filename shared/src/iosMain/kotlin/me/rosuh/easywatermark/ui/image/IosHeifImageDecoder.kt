package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.render.IosImageIODecoder
import okio.BufferedSource
import org.jetbrains.skia.Bitmap

/**
 * Coil [Decoder] for HEIC/HEIF on iOS.
 *
 * Default Coil uses Skia `Image.makeFromEncoded`, which cannot decode HEIF
 * (coil#2318, skiko#942). This decoder uses ImageIO
 * `CGImageSourceCreateThumbnailAtIndex` at a **policy-resolved** long-edge —
 * native subsample + EXIF bake, no full-res decode and no JPEG transcode.
 *
 * [Factory] takes [IosHeifDecodePolicy] so filmstrip (128, never-sampled) and
 * preview/export-adjacent HEIF (up to 3840, infer sampled) share one implementation.
 * Per-request [iosHeifMaxEdgePx] extras override the edge without swapping factories.
 */
internal class IosHeifImageDecoder(
    private val result: SourceFetchResult,
    private val options: Options,
    private val policy: IosHeifDecodePolicy,
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(Dispatchers.Default) {
        val maxEdge = policy.resolveMaxEdgePx(
            requestLongEdgePx = options.heifRequestLongEdgePx(),
            extraMaxEdgePx = options.heifExtraMaxEdgePx(),
        )
        val path = result.source.file().toString()
        if (path.isBlank()) return@withContext null
        val sourceLong = runCatching {
            val meta = IosImageIODecoder.metadata(path)
            maxOf(meta.width, meta.height)
        }.getOrDefault(0)
        val skia = runCatching {
            IosImageIODecoder.decodeThumbnailSkia(
                sourcePath = path,
                maxEdgePx = maxEdge,
                shouldCache = policy.imageIoShouldCache,
            )
        }.getOrNull() ?: return@withContext null
        try {
            val bitmap = Bitmap()
            if (!bitmap.allocPixels(skia.imageInfo)) return@withContext null
            if (!skia.readPixels(bitmap)) return@withContext null
            bitmap.setImmutable()
            val outLong = maxOf(bitmap.width, bitmap.height)
            DecodeResult(
                image = bitmap.asImage(),
                isSampled = policy.resolveIsSampled(sourceLong, outLong),
            )
        } finally {
            skia.close()
        }
    }

    class Factory(
        private val policy: IosHeifDecodePolicy = IosHeifDecodePolicy.ProductUi,
    ) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!looksLikeHeif(result)) return null
            return IosHeifImageDecoder(result, options, policy)
        }
    }

    internal companion object {
        fun looksLikeHeif(result: SourceFetchResult): Boolean {
            val mime = result.mimeType?.lowercase()
            if (mime == "image/heic" || mime == "image/heif" || mime == "image/heic-sequence") {
                return true
            }
            return looksLikeHeifFtyp(result.source.source().peek())
        }

        /** ISO BMFF `ftyp` major brand — must not consume the source (Coil Decoder.Factory contract). */
        fun looksLikeHeifFtyp(peek: BufferedSource): Boolean {
            if (!peek.request(12)) return false
            val bytes = peek.readByteArray(12)
            if (bytes.size < 12) return false
            if (bytes[4] != 'f'.code.toByte() ||
                bytes[5] != 't'.code.toByte() ||
                bytes[6] != 'y'.code.toByte() ||
                bytes[7] != 'p'.code.toByte()
            ) {
                return false
            }
            val brand = bytes.decodeToString(8, 12).lowercase()
            return brand == "heic" ||
                brand == "heif" ||
                brand == "mif1" ||
                brand == "msf1" ||
                brand == "heix" ||
                brand == "hevc" ||
                brand == "hevx"
        }
    }
}
