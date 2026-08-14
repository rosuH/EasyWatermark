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
        val ctx = PlatformContext.INSTANCE
        val loader = buildProductImageLoader(ctx)

        for (path in paths) {
            val meta = runCatching { IosImageIODecoder.metadata(path) }.getOrNull()
            val fmt = sniffFmt(path)
            log(
                "DEVICE_PERF_SRC path=${path.substringAfterLast('/')} fmt=$fmt " +
                    "w=${meta?.width ?: 0} h=${meta?.height ?: 0}",
            )
            coilCold += timeCoil(loader, ctx, path, cache = false)
            timeCoil(loader, ctx, path, cache = true)
            coilWarm += timeCoil(loader, ctx, path, cache = true)
        }

        val plain128 = IoMode(128, subsample = false)
        val sub128 = IoMode(128, subsample = true)
        val plainBucket = IoMode(bucket, subsample = false)
        val subBucket = IoMode(bucket, subsample = true)
        val io = measureIoModes(paths, listOf(plain128, sub128, plainBucket, subBucket))
        val io128 = io.pooled(plain128)
        val ioBucket = io.pooled(plainBucket)
        logSubsampleShape(paths, listOf(128, bucket))

        val lap1 = ArrayList<IosProductRootHost.SwitchImageTiming>(paths.size)
        for (path in paths) {
            val t = host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
            lap1 += t
            log(switchLine(lap = 1, timing = t, path = path))
        }
        val lap2 = ArrayList<IosProductRootHost.SwitchImageTiming>(paths.size)
        for (path in paths) {
            val t = host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
            lap2 += t
            log(switchLine(lap = 2, timing = t, path = path))
        }

        // Lap 3 warms ±2 like production does, which is the only lap where the cache's eviction
        // policy is observable: laps 1/2 never populate neighbors, so they miss under any policy.
        val lap3 = ArrayList<IosProductRootHost.SwitchImageTiming>(paths.size)
        for (path in paths) {
            val t = host.switchImageAndAwaitForTests(path, awaitNeighbors = true)
            lap3 += t
            log(switchLine(lap = 3, timing = t, path = path))
        }
        val lap4 = ArrayList<IosProductRootHost.SwitchImageTiming>(paths.size)
        for (path in paths) {
            val t = host.switchImageAndAwaitForTests(path, awaitNeighbors = true)
            lap4 += t
            log(switchLine(lap = 4, timing = t, path = path))
        }

        val recompose = host.recomposeWatermarkFromCachedSourceForTests()
        log("DEVICE_PERF_RECOMPOSE hit=$recompose")

        fun hitKind(hit: String): String = when (hit) {
            "wm", "wm_optimistic" -> "watermarked"
            "source" -> "source"
            else -> "miss"
        }
        val l2Kinds = lap2.groupingBy { hitKind(it.hit) }.eachCount()
        log(
            "DEVICE_PERF_SUMMARY n=${paths.size} bucket=$bucket source=$source " +
                "io128_med=${median(io128)} io${bucket}_med=${median(ioBucket)} " +
                "io128_sub_med=${median(io.pooled(sub128))} " +
                "io${bucket}_sub_med=${median(io.pooled(subBucket))} " +
                "io128_first_med=${median(io.first(plain128))} " +
                "io128_second_med=${median(io.second(plain128))} " +
                "io128_warm_med=${median(io.warmOf(plain128))} " +
                "io128_sub_warm_med=${median(io.warmOf(sub128))} " +
                "io${bucket}_first_med=${median(io.first(plainBucket))} " +
                "io${bucket}_second_med=${median(io.second(plainBucket))} " +
                "io${bucket}_warm_med=${median(io.warmOf(plainBucket))} " +
                "io${bucket}_sub_warm_med=${median(io.warmOf(subBucket))} " +
                "coil_cold_med=${median(coilCold)} coil_warm_med=${median(coilWarm)} " +
                "switch_l1_med=${median(lap1.map { it.totalMs })} " +
                "switch_l1_hits=${lap1.count { it.hit != "miss" }}/${lap1.size} " +
                "switch_l2_med=${median(lap2.map { it.totalMs })} " +
                "switch_l2_hits=${lap2.count { it.hit != "miss" }}/${lap2.size} " +
                "switch_l2_wm=${l2Kinds["watermarked"] ?: 0} " +
                "switch_l2_source=${l2Kinds["source"] ?: 0} " +
                "switch_l2_miss=${l2Kinds["miss"] ?: 0} " +
                "switch_l3_med=${median(lap3.map { it.totalMs })} " +
                "switch_l3_hits=${lap3.count { it.hit != "miss" }}/${lap3.size} " +
                "switch_l4_med=${median(lap4.map { it.totalMs })} " +
                "switch_l4_hits=${lap4.count { it.hit != "miss" }}/${lap4.size} " +
                "switch_l4_hits_detail=${lap4.joinToString(",") { it.hit }} " +
                switchSplitSummary(lap = 1, timings = lap1) +
                switchSplitSummary(lap = 2, timings = lap2) +
                switchSplitSummary(lap = 4, timings = lap4) +
                "recompose=$recompose " +
                "io128_ms=${io128.joinToString(",")} " +
                "coil_cold_ms=${coilCold.joinToString(",")} " +
                "switch_l1_ms=${lap1.joinToString(",") { it.totalMs.toString() }} " +
                "switch_l2_ms=${lap2.joinToString(",") { it.totalMs.toString() }} " +
                "switch_l2_hits_detail=${lap2.joinToString(",") { it.hit }}",
        )
        log("DEVICE_PERF_DONE")
    }

    /**
     * Per-size ImageIO thumbnail timings with the order bias removed.
     *
     * The previous shape timed 128 and then the preview bucket on the next line for the **same**
     * file, so the bucket sample was always the warm one while 128 always paid that file's first
     * open — which is why a 1920 decode looked cheaper than a 128 one. Here the size order
     * alternates per file so each size takes an equal share of first/second position, and a second
     * lap over the whole list reports warm separately from first-touch.
     */
    /** One measured decode configuration: requested long edge × subsample strategy. */
    private data class IoMode(val size: Int, val subsample: Boolean) {
        val label: String get() = if (subsample) "${size}sub" else "$size"
    }

    private class IoSamples(modes: List<IoMode>) {
        private val firstPosition = modes.associateWith { ArrayList<Long>() }
        private val secondPosition = modes.associateWith { ArrayList<Long>() }
        private val warm = modes.associateWith { ArrayList<Long>() }

        fun addLapOne(mode: IoMode, ms: Long, wasFirst: Boolean) {
            val bucket = if (wasFirst) firstPosition else secondPosition
            bucket[mode]?.add(ms)
        }

        fun addWarm(mode: IoMode, ms: Long) {
            warm[mode]?.add(ms)
        }

        fun first(mode: IoMode): List<Long> = firstPosition[mode].orEmpty()
        fun second(mode: IoMode): List<Long> = secondPosition[mode].orEmpty()
        fun warmOf(mode: IoMode): List<Long> = warm[mode].orEmpty()

        /** Order-balanced median input: both positions of lap one. */
        fun pooled(mode: IoMode): List<Long> = first(mode) + second(mode)
    }

    private fun measureIoModes(paths: List<String>, modes: List<IoMode>): IoSamples {
        val distinct = modes.distinct()
        val samples = IoSamples(distinct)
        paths.forEachIndexed { index, path ->
            // Rotate so no single mode always pays a file's first open.
            val order = List(distinct.size) { distinct[(it + index) % distinct.size] }
            order.forEachIndexed { position, mode ->
                val ms = timeMs {
                    IosImageIODecoder.decodeThumbnail(path, mode.size, allowSubsample = mode.subsample)
                }
                samples.addLapOne(mode, ms, wasFirst = position == 0)
                log(
                    "DEVICE_PERF_IO lap=1 mode=${mode.label} pos=${position + 1} ms=$ms " +
                        "path=${path.substringAfterLast('/')}",
                )
            }
        }
        for (path in paths) {
            for (mode in distinct) {
                val ms = timeMs {
                    IosImageIODecoder.decodeThumbnail(path, mode.size, allowSubsample = mode.subsample)
                }
                samples.addWarm(mode, ms)
                log(
                    "DEVICE_PERF_IO lap=2 mode=${mode.label} ms=$ms " +
                        "path=${path.substringAfterLast('/')}",
                )
            }
        }
        return samples
    }

    /** Output size must be identical with and without subsampling — only the decode cost moves. */
    private fun logSubsampleShape(paths: List<String>, sizes: List<Int>) {
        for (path in paths) {
            for (size in sizes) {
                val plain = runCatching { IosImageIODecoder.decodeThumbnail(path, size) }.getOrNull()
                val sub = runCatching {
                    IosImageIODecoder.decodeThumbnail(path, size, allowSubsample = true)
                }.getOrNull()
                val meta = runCatching { IosImageIODecoder.metadata(path) }.getOrNull()
                val factor = if (meta == null) {
                    -1
                } else {
                    IosImageIODecoder.subsampleFactorFor(maxOf(meta.width, meta.height), size)
                }
                log(
                    "DEVICE_PERF_IO_SHAPE size=$size factor=$factor " +
                        "plain=${plain?.width ?: -1}x${plain?.height ?: -1} " +
                        "sub=${sub?.width ?: -1}x${sub?.height ?: -1} " +
                        "path=${path.substringAfterLast('/')}",
                )
            }
        }
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

    private fun switchLine(
        lap: Int,
        timing: IosProductRootHost.SwitchImageTiming,
        path: String,
    ): String =
        "DEVICE_PERF_SWITCH lap=$lap hit=${timing.hit} ms=${timing.totalMs} " +
            "decode=${timing.decodeMs} compose=${timing.composeMs} " +
            "icon=${timing.iconMs} dispatch=${timing.dispatchMs} other=${timing.otherMs} " +
            "path=${path.substringAfterLast('/')}"

    /** `other` is the named remainder: Session propagation, cache/mutex, Skia→Compose repack. */
    private fun switchSplitSummary(
        lap: Int,
        timings: List<IosProductRootHost.SwitchImageTiming>,
    ): String =
        "switch_l${lap}_decode_med=${median(timings.map { it.decodeMs })} " +
            "switch_l${lap}_compose_med=${median(timings.map { it.composeMs })} " +
            "switch_l${lap}_icon_med=${median(timings.map { it.iconMs })} " +
            "switch_l${lap}_dispatch_med=${median(timings.map { it.dispatchMs })} " +
            "switch_l${lap}_other_med=${median(timings.map { it.otherMs })} "

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
