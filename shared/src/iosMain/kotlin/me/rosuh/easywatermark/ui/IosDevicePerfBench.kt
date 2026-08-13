@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.delay
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosImageIODecoder
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import me.rosuh.easywatermark.render.PreviewResolutionPolicy
import me.rosuh.easywatermark.session.IosPickGenerationGate
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.image.buildProductImageLoader
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageDestinationAddImageFromSource
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateWithURL
import kotlin.time.TimeSource
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * One-shot device timing for filmstrip switch + HEIC thumbs.
 *
 * Launch: `devicectl … process launch … me.rosuh.easywatermark.ios -- -ewmDevicePerfBench`
 * Prints `DEVICE_PERF_*` lines then `DEVICE_PERF_DONE`.
 */
internal object IosDevicePerfBench {
    private const val ARG = "-ewmDevicePerfBench"

    fun requested(): Boolean =
        NSProcessInfo.processInfo.arguments.any { it?.toString() == ARG }

    private fun log(line: String) {
        println(line)
        NSLog("%s", line)
        runCatching {
            val dir = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String ?: return
            val file = dir.trimEnd('/') + "/ewm-device-perf.txt"
            val prev = NSData.dataWithContentsOfFile(file)?.let { IosByteArrayInterop.fromNSData(it) }
                ?.decodeToString()
                .orEmpty()
            val next = (prev + line + "\n").encodeToByteArray()
            IosByteArrayInterop.toNSData(next).writeToFile(file, atomically = true)
        }
    }

    suspend fun run(host: IosProductRootHost) {
        runCatching {
            val dir = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String
            if (dir != null) {
                NSFileManager.defaultManager.removeItemAtPath(dir.trimEnd('/') + "/ewm-device-perf.txt", null)
            }
        }
        log("DEVICE_PERF_START")
        host.pinPreviewBucketForBench(PreviewResolutionPolicy.PHONE_PREVIEW_MAX_LONG_EDGE_PX)
        var paths = host.sessionSourcePathsForBench()
        var source = "session"
        if (paths.isEmpty()) {
            // Never synthesize 2400/4032 fixtures for this gate. Album camera HEICs
            // may be staged under Documents/ewm-12mp-drop (long edge ≥ 3000 only).
            paths = stageAlbumDropIfTwelveMp(host)
            source = "album_heic"
        }
        if (paths.isEmpty()) {
            log("DEVICE_PERF_DONE error=no_session_12mp")
            return
        }
        val tooSmall = paths.mapNotNull { path ->
            val meta = runCatching { IosImageIODecoder.metadata(path) }.getOrNull()
            val long = if (meta == null) 0 else maxOf(meta.width, meta.height)
            if (long < 3000) path.substringAfterLast('/') to long else null
        }
        if (tooSmall.isNotEmpty()) {
            log(
                "DEVICE_PERF_DONE error=not_12mp " +
                    tooSmall.joinToString(",") { "${it.first}:${it.second}" },
            )
            return
        }
        val bucket = host.previewBucketForBench()
        log("DEVICE_PERF_META source=$source n=${paths.size} bucket=$bucket")

        val coilCold = ArrayList<Long>(paths.size)
        val coilWarm = ArrayList<Long>(paths.size)
        val io128 = ArrayList<Long>(paths.size)
        val ioBucket = ArrayList<Long>(paths.size)
        val ctx = PlatformContext.INSTANCE
        val loader = buildProductImageLoader(ctx)

        for (path in paths) {
            val meta = runCatching { IosImageIODecoder.metadata(path) }.getOrNull()
            val fmt = sniffFmt(path)
            log(
                "DEVICE_PERF_SRC path=${path.substringAfterLast('/')} fmt=$fmt " +
                    "w=${meta?.width ?: 0} h=${meta?.height ?: 0}",
            )
            io128 += timeMs { IosImageIODecoder.decodeThumbnail(path, 128) }
            ioBucket += timeMs { IosImageIODecoder.decodeThumbnail(path, bucket) }
            coilCold += timeCoil(loader, ctx, path, cache = false)
            timeCoil(loader, ctx, path, cache = true)
            coilWarm += timeCoil(loader, ctx, path, cache = true)
        }

        val lap1 = ArrayList<Pair<String, Long>>(paths.size)
        for (path in paths) {
            val t = host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
            lap1 += t.hit to t.totalMs
            log("DEVICE_PERF_SWITCH lap=1 hit=${t.hit} ms=${t.totalMs} path=${path.substringAfterLast('/')}")
        }
        val lap2 = ArrayList<Pair<String, Long>>(paths.size)
        for (path in paths) {
            val t = host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
            lap2 += t.hit to t.totalMs
            log("DEVICE_PERF_SWITCH lap=2 hit=${t.hit} ms=${t.totalMs} path=${path.substringAfterLast('/')}")
        }

        val recompose = host.recomposeWatermarkFromCachedSourceForTests()
        log("DEVICE_PERF_RECOMPOSE hit=$recompose")

        fun hitKind(hit: String): String = when (hit) {
            "wm", "wm_optimistic" -> "watermarked"
            "source" -> "source"
            else -> "miss"
        }
        val l2Kinds = lap2.groupingBy { hitKind(it.first) }.eachCount()
        log(
            "DEVICE_PERF_SUMMARY n=${paths.size} bucket=$bucket source=$source " +
                "io128_med=${median(io128)} io${bucket}_med=${median(ioBucket)} " +
                "coil_cold_med=${median(coilCold)} coil_warm_med=${median(coilWarm)} " +
                "switch_l1_med=${median(lap1.map { it.second })} " +
                "switch_l1_hits=${lap1.count { it.first != "miss" }}/${lap1.size} " +
                "switch_l2_med=${median(lap2.map { it.second })} " +
                "switch_l2_hits=${lap2.count { it.first != "miss" }}/${lap2.size} " +
                "switch_l2_wm=${l2Kinds["watermarked"] ?: 0} " +
                "switch_l2_source=${l2Kinds["source"] ?: 0} " +
                "switch_l2_miss=${l2Kinds["miss"] ?: 0} " +
                "recompose=$recompose " +
                "io128_ms=${io128.joinToString(",")} " +
                "coil_cold_ms=${coilCold.joinToString(",")} " +
                "switch_l1_ms=${lap1.joinToString(",") { it.second.toString() }} " +
                "switch_l2_ms=${lap2.joinToString(",") { it.second.toString() }} " +
                "switch_l2_hits_detail=${lap2.joinToString(",") { it.first }}",
        )
        log("DEVICE_PERF_DONE")
    }

    private suspend fun stageAlbumDropIfTwelveMp(host: IosProductRootHost): List<String> {
        val dir = documentsDir()?.trimEnd('/') + "/ewm-12mp-drop"
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(dir)) {
            log("DEVICE_PERF_DROP missing=$dir")
            return emptyList()
        }
        @Suppress("UNCHECKED_CAST")
        val names = manager.contentsOfDirectoryAtPath(dir, error = null) as? List<*> ?: emptyList<Any>()
        val heics = names.mapNotNull { it as? String }
            .filter { name ->
                val lower = name.lowercase()
                lower.endsWith(".heic") || lower.endsWith(".heif")
            }
            .sorted()
        if (heics.size < 4) {
            log("DEVICE_PERF_DROP too_few=${heics.size}")
            return emptyList()
        }
        val batches = ArrayList<ByteArray>(heics.size)
        for (name in heics) {
            val path = "$dir/$name"
            val meta = runCatching { IosImageIODecoder.metadata(path) }.getOrNull()
            val long = if (meta == null) 0 else maxOf(meta.width, meta.height)
            if (long < 3000) {
                log("DEVICE_PERF_DROP reject=$name long=$long")
                return emptyList()
            }
            val data = NSData.dataWithContentsOfFile(path) ?: return emptyList()
            val bytes = IosByteArrayInterop.fromNSData(data)
            if (bytes.isEmpty()) return emptyList()
            batches += bytes
            log("DEVICE_PERF_DROP name=$name ${meta!!.width}x${meta.height} bytes=${bytes.size}")
        }
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        host.deliverPickedPhotosBatch(
            batches,
            append = false,
            renderPreview = true,
            pickGeneration = gen,
        )
        delay(400)
        return host.sessionSourcePathsForBench()
    }

    private fun documentsDir(): String? =
        NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String

    private suspend fun stageHeicFixtures(host: IosProductRootHost): List<String> {
        val specs = listOf(
            2400 to 1600,
            2400 to 1600,
            2400 to 1600,
            2400 to 1600,
            2400 to 1600,
            2400 to 1600,
        )
        val batches = ArrayList<ByteArray>(specs.size)
        for ((w, h) in specs) {
            val png = writeBusyPng(w, h) ?: continue
            val heic = encodeHeicFromPath(png) ?: continue
            val data = NSData.dataWithContentsOfFile(heic) ?: continue
            val bytes = IosByteArrayInterop.fromNSData(data)
            if (bytes.isNotEmpty()) batches += bytes
            log("DEVICE_PERF_FIXTURE ${w}x$h heicBytes=${bytes.size}")
        }
        if (batches.isEmpty()) return emptyList()
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        host.deliverPickedPhotosBatch(batches, append = false, renderPreview = true, pickGeneration = gen)
        delay(400)
        return host.sessionSourcePathsForBench()
    }

    private suspend fun timeCoil(
        loader: ImageLoader,
        ctx: PlatformContext,
        path: String,
        cache: Boolean,
    ): Long {
        val policy = if (cache) CachePolicy.ENABLED else CachePolicy.DISABLED
        val thumb = ProductThumb(MediaRef(path), maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE)
        val mark = TimeSource.Monotonic.markNow()
        val result = loader.execute(
            ImageRequest.Builder(ctx)
                .data(thumb)
                .memoryCachePolicy(policy)
                .diskCachePolicy(CachePolicy.DISABLED)
                .size(ProductThumb.UI_THUMB_MAX_EDGE)
                .precision(Precision.INEXACT)
                .build(),
        )
        val ms = mark.elapsedNow().inWholeMilliseconds
        val ok = result is SuccessResult
        log(
            "DEVICE_PERF_COIL cache=$cache ok=$ok ms=$ms " +
                "w=${if (ok) (result as SuccessResult).image.width else 0} " +
                "path=${path.substringAfterLast('/')}",
        )
        return ms
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val mark = TimeSource.Monotonic.markNow()
        runCatching(block)
        return mark.elapsedNow().inWholeMilliseconds
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return -1
        val s = values.sorted()
        return s[s.size / 2]
    }

    private fun sniffFmt(path: String): String {
        val data = NSData.dataWithContentsOfFile(path) ?: return "missing"
        val bytes = IosByteArrayInterop.fromNSData(data)
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return bytes.decodeToString(8, 12)
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8) {
            return "jpeg"
        }
        return "other"
    }

    private fun writeBusyPng(width: Int, height: Int): String? {
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
        val path = NSTemporaryDirectory().trimEnd('/') + "/ewm-perf-${NSUUID().UUIDString}.png"
        val ok = IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true)
        return path.takeIf { ok }
    }

    private fun encodeHeicFromPath(pngPath: String): String? {
        val srcUrl = NSURL.fileURLWithPath(pngPath)
        val srcCf = CFBridgingRetain(srcUrl) ?: return null
        val source = try {
            @Suppress("UNCHECKED_CAST")
            CGImageSourceCreateWithURL(srcCf as CFURLRef, null)
        } finally {
            CFBridgingRelease(srcCf)
        } ?: return null
        val out = NSTemporaryDirectory().trimEnd('/') + "/ewm-perf-${NSUUID().UUIDString}.heic"
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
        return try {
            CGImageDestinationAddImageFromSource(dest, source, 0u, null)
            out.takeIf { CGImageDestinationFinalize(dest) }
        } finally {
            CFRelease(dest)
        }
    }
}
