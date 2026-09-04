@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.test.runTest
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageDestinationAddImageFromSource
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateWithURL
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A/B on iOS: production [buildProductImageLoader] vs Skia-only vs file:// builtin.
 * JPEG/PNG production is Source+Skia (same as B). HEIC production is ImageIO (`IosHeifImageDecoder`);
 * B/C fail Skia. HEIC encode is best-effort (skip arm if ImageIO cannot write).
 */
class IosProductThumbAbDecodeBenchTest {

    @Test
    fun largePng_imageIoThumb_vs_coilSourceDecoder() = runTest {
        val path = IosProductThumbAbFixtures.writeBusyPng(2400, 1600)
        try {
            runAb("png", path, srcW = 2400, srcH = 1600)
        } finally {
            platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun largeHeic_imageIoThumb_vs_coilSourceDecoder() = runTest {
        val png = IosProductThumbAbFixtures.writeBusyPng(2400, 1600)
        val heic = IosProductThumbAbFixtures.encodeHeicFromPath(png)
        try {
            if (heic == null) {
                println("PRODUCT_THUMB_AB platform=ios fmt=heic SKIP encode_failed")
                return@runTest
            }
            runAb("heic", heic, srcW = 2400, srcH = 1600)
        } finally {
            platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(png, null)
            heic?.let { platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(it, null) }
        }
    }

    private suspend fun runAb(fmt: String, path: String, srcW: Int, srcH: Int) {
        val ctx = PlatformContext.INSTANCE
        val edge = ProductThumb.UI_THUMB_MAX_EDGE
        val a = timeArm("A_imageIo_repack") {
            val loader = buildProductImageLoader(ctx)
            val thumb = ProductThumb(ref = MediaRef(path), maxEdgePx = edge)
            loader.execute(
                ImageRequest.Builder(ctx)
                    .data(thumb)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(edge)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
        val b = timeArm("B_coil_source_skia") {
            val loader = ImageLoader.Builder(ctx)
                .components { add(ProductThumbSourceFetcher.Factory()) }
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            val thumb = ProductThumb(ref = MediaRef(path), maxEdgePx = edge)
            loader.execute(
                ImageRequest.Builder(ctx)
                    .data(thumb)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(edge)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
        val c = timeArm("C_file_uri_builtin") {
            val loader = ImageLoader.Builder(ctx)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            loader.execute(
                ImageRequest.Builder(ctx)
                    .data("file://$path")
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(edge)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
        println(
            "PRODUCT_THUMB_AB platform=ios fmt=$fmt src=${srcW}x$srcH edge=$edge " +
                "A_ok=${a.ok} A_med_ms=${a.medianMs} A_w=${a.width} A_h=${a.height} " +
                "B_ok=${b.ok} B_med_ms=${b.medianMs} B_w=${b.width} B_h=${b.height} B_err=${b.error} " +
                "C_ok=${c.ok} C_med_ms=${c.medianMs} C_w=${c.width} C_h=${c.height} C_err=${c.error}",
        )
        assertTrue(a.ok, "A (production product loader) must succeed for $fmt")
    }

    private data class Arm(
        val ok: Boolean,
        val medianMs: Long,
        val width: Int,
        val height: Int,
        val error: String,
    )

    private suspend fun timeArm(name: String, block: suspend () -> Any): Arm {
        val samples = ArrayList<Long>(6)
        var w = 0
        var h = 0
        var err = ""
        var ok = false
        repeat(6) { i ->
            val mark = TimeSource.Monotonic.markNow()
            when (val result = runCatching { block() }.getOrElse { t ->
                err = t.message ?: t::class.simpleName.orEmpty()
                return@repeat
            }) {
                is SuccessResult -> {
                    ok = true
                    w = result.image.width
                    h = result.image.height
                    if (i > 0) samples += mark.elapsedNow().inWholeMilliseconds
                }
                is ErrorResult -> err = result.throwable.message ?: "ErrorResult"
            }
        }
        val med = if (samples.isEmpty()) -1L else samples.sorted()[samples.size / 2]
        println("PRODUCT_THUMB_AB_ARM name=$name samples=$samples med=$med w=$w h=$h err=$err")
        return Arm(ok, med, w, h, err)
    }
}

internal object IosProductThumbAbFixtures {
    fun writeBusyPng(width: Int, height: Int): String {
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(Color(0xFF224466))
            for (i in 0 until 40) {
                drawCircle(
                    color = Color(0xFF88AA33),
                    radius = width / 40f,
                    center = androidx.compose.ui.geometry.Offset(
                        width * (i + 1) / 41f,
                        height * ((i * 7) % 40 + 1) / 41f,
                    ),
                )
            }
        }
        val bytes = IosWatermarkRenderer.encodePng(bmp)
        val dir = NSTemporaryDirectory()
        val path = dir.trimEnd('/') + "/ewm-ab-${NSUUID().UUIDString}.png"
        val ns = me.rosuh.easywatermark.render.IosByteArrayInterop.toNSData(bytes)
        check(ns.writeToFile(path, atomically = true)) { "write $path" }
        return path
    }

    /**
     * ImageIO HEIC from a PNG path. Returns null if this simulator/device cannot write HEIF
     * (then the HEIC test logs SKIP instead of failing).
     */
    fun encodeHeicFromPath(pngPath: String): String? {
        val srcUrl = NSURL.fileURLWithPath(pngPath)
        val srcCf = CFBridgingRetain(srcUrl) ?: return null
        val source = try {
            @Suppress("UNCHECKED_CAST")
            CGImageSourceCreateWithURL(srcCf as CFURLRef, null)
        } finally {
            CFBridgingRelease(srcCf)
        } ?: return null
        val dir = NSTemporaryDirectory()
        val out = dir.trimEnd('/') + "/ewm-ab-${NSUUID().UUIDString}.heic"
        val destUrl = NSURL.fileURLWithPath(out)
        val destCf = CFBridgingRetain(destUrl) ?: return null
        val uti = CFStringCreateWithCString(null, "public.heic", kCFStringEncodingUTF8)
        val dest = try {
            @Suppress("UNCHECKED_CAST")
            CGImageDestinationCreateWithURL(destCf as CFURLRef, uti, 1u, null)
        } finally {
            CFBridgingRelease(destCf)
            if (uti != null) CFRelease(uti)
        } ?: return null
        try {
            CGImageDestinationAddImageFromSource(dest, source, 0u, null)
            val ok = CGImageDestinationFinalize(dest)
            return out.takeIf { ok }
        } finally {
            CFRelease(dest)
        }
    }
}

/** Test-only: ProductThumb → file [SourceFetchResult] so Coil's Skia decoder owns downsample. */
private class ProductThumbSourceFetcher(
    private val data: ProductThumb,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val path = data.ref.value.toPath()
        return SourceFetchResult(
            source = ImageSource(file = path, fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ProductThumb> {
        override fun create(
            data: ProductThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ProductThumbSourceFetcher(data, options)
    }
}
