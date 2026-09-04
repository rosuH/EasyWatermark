package me.rosuh.easywatermark.ui.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.MediaRef
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A/B: production [buildProductImageLoader] (SourceFetch + Skia downsample) vs
 * a bare Coil file + default decoder. After the 2026-08-13 flip, A should match B
 * (~4ms on 3000×2000 JPEG → 128), not the old ImageIO+repack (~15ms).
 *
 * Prints `PRODUCT_THUMB_AB`. Desktop JPEG/PNG only — HEIC is an iOS arm.
 */
class ProductThumbAbDecodeBenchTest {

    @Test
    fun largeJpeg_productSourceSkia_vs_coilFileDecoder_recordsMedians() = runBlocking {
        val file = writeBusyJpeg(width = 3000, height = 2000)
        try {
            val ctx = PlatformContext.INSTANCE
            val edge = 128
            val a = timeArm("A_productThumb_sourceSkia", repeats = 7) {
                val loader = buildProductImageLoader(ctx)
                val thumb = ProductThumb(ref = MediaRef(file.absolutePath), maxEdgePx = edge)
                val req = ImageRequest.Builder(ctx)
                    .data(thumb)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(edge)
                    .precision(Precision.INEXACT)
                    .build()
                loader.execute(req)
            }
            val b = timeArm("B_coil_file_decoder", repeats = 7) {
                val loader = ImageLoader.Builder(ctx)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build()
                val req = ImageRequest.Builder(ctx)
                    .data(file)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .size(edge)
                    .precision(Precision.INEXACT)
                    .build()
                loader.execute(req)
            }
            println(
                "PRODUCT_THUMB_AB platform=desktop fmt=jpeg src=3000x2000 edge=$edge " +
                    "A_ok=${a.ok} A_med_ms=${a.medianMs} A_w=${a.width} A_h=${a.height} " +
                    "B_ok=${b.ok} B_med_ms=${b.medianMs} B_w=${b.width} B_h=${b.height} " +
                    "B_err=${b.error}",
            )
            assertTrue(a.ok, "A (product SourceFetch + Skia) must succeed")
            assertTrue(a.width <= 128 && a.height <= 128, "A must downsample to request edge")
            assertTrue(b.ok, "B (bare Coil file decoder) must succeed")
            assertTrue(b.width <= 128 && b.height <= 128)
        } finally {
            file.delete()
        }
    }

    private data class Arm(
        val ok: Boolean,
        val medianMs: Long,
        val width: Int,
        val height: Int,
        val error: String = "",
    )

    private suspend fun timeArm(name: String, repeats: Int, block: suspend () -> Any): Arm {
        val samples = ArrayList<Long>(repeats)
        var lastW = 0
        var lastH = 0
        var lastErr = ""
        var ok = false
        repeat(repeats) { i ->
            val mark = TimeSource.Monotonic.markNow()
            val result = runCatching { block() }.getOrElse { t ->
                lastErr = t.message ?: t::class.simpleName.orEmpty()
                return@repeat
            }
            val ms = mark.elapsedNow().inWholeMilliseconds
            when (result) {
                is SuccessResult -> {
                    ok = true
                    lastW = result.image.width
                    lastH = result.image.height
                    if (i > 0) samples += ms // drop first (JIT / first-open)
                }
                is ErrorResult -> {
                    lastErr = result.throwable.message ?: "ErrorResult"
                }
            }
        }
        val med = if (samples.isEmpty()) -1L else samples.sorted()[samples.size / 2]
        println("PRODUCT_THUMB_AB_ARM name=$name samples=$samples med=$med w=$lastW h=$lastH err=$lastErr")
        return Arm(ok = ok, medianMs = med, width = lastW, height = lastH, error = lastErr)
    }

    private fun writeBusyJpeg(width: Int, height: Int): File {
        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas
        canvas.clear(0xFF224466.toInt())
        val paint = Paint().apply { color = 0xFF88AA33.toInt() }
        for (i in 0 until 80) {
            canvas.drawCircle(
                width * (i + 1) / 81f,
                height * ((i * 11) % 80 + 1) / 81f,
                width / 40f,
                paint,
            )
        }
        val image = surface.makeImageSnapshot()
        val data = requireNotNull(image.encodeToData(EncodedImageFormat.JPEG, 85))
        val file = File.createTempFile("ewm-ab-src-", ".jpg")
        file.writeBytes(data.bytes)
        image.close()
        surface.close()
        return file
    }
}
