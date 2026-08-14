@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui

import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosCgImageTransferMode
import me.rosuh.easywatermark.render.IosCgImageTransferProbe
import me.rosuh.easywatermark.render.IosImageIODecoder
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

/**
 * Device A/B for Phase-1 CGImage → Skia transfer (same metrics as
 * [me.rosuh.easywatermark.render.IosCgImageTransferAbBenchTest]).
 *
 * Launch: `devicectl … process launch … -- -ewmCgTransferAb`
 * Writes `Documents/ewm-cg-transfer-ab.txt` and `CG_TRANSFER_AB_*` / `CG_TRANSFER_AB_DONE`.
 */
internal object IosCgImageTransferDeviceBench {
    private const val ARG = "-ewmCgTransferAb"

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
            val file = dir.trimEnd('/') + "/ewm-cg-transfer-ab.txt"
            val prev = NSData.dataWithContentsOfFile(file)?.let { IosByteArrayInterop.fromNSData(it) }
                ?.decodeToString()
                .orEmpty()
            val next = (prev + line + "\n").encodeToByteArray()
            IosByteArrayInterop.toNSData(next).writeToFile(file, atomically = true)
        }
    }

    fun run() {
        runCatching {
            val dir = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String
            if (dir != null) {
                NSFileManager.defaultManager.removeItemAtPath(
                    dir.trimEnd('/') + "/ewm-cg-transfer-ab.txt",
                    null,
                )
            }
        }
        log("CG_TRANSFER_AB_START platform=ios device=physical")
        val png = writeBusyPng(2400, 1600)
        if (png == null) {
            log("CG_TRANSFER_AB_DONE error=png_fixture_failed")
            return
        }
        try {
            for (edge in listOf(128, 720, 1920)) {
                measureCompose("png", png, 2400, 1600, edge)
            }
            val heic = encodeHeicFromPath(png)
            if (heic == null) {
                log("CG_TRANSFER_AB fmt=heic SKIP encode_failed")
            } else {
                try {
                    for (edge in listOf(128, 720, 1920)) {
                        measureCompose("heic", heic, 2400, 1600, edge)
                    }
                } finally {
                    NSFileManager.defaultManager.removeItemAtPath(heic, null)
                }
            }
            for (surface in listOf("bitmap", "image", "compose")) {
                measureSurface("png", png, 1920, surface)
            }
            log("CG_TRANSFER_AB_DONE ok=1")
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(png, null)
            IosCgImageTransferProbe.resetForTests()
        }
    }

    private fun measureCompose(fmt: String, path: String, srcW: Int, srcH: Int, edge: Int) {
        measureSurface(fmt, path, edge, "compose", srcW, srcH)
    }

    private fun measureSurface(
        fmt: String,
        path: String,
        edge: Int,
        surface: String,
        srcW: Int = 0,
        srcH: Int = 0,
    ) {
        val a = timeArm(IosCgImageTransferMode.LegacyByteArray, surface, path, edge)
        val b = timeArm(IosCgImageTransferMode.SkiaOwned, surface, path, edge)
        val allocSaved = a.accountedAllocBytes - b.accountedAllocBytes
        val handoffDeltaUs = (a.handoffNsMedian - b.handoffNsMedian) / 1000
        log(
            "CG_TRANSFER_AB platform=ios fmt=$fmt surface=$surface " +
                "src=${srcW}x$srcH edge=$edge out=${a.width}x${a.height} " +
                "A_writes=${a.writes} A_alloc_B=${a.accountedAllocBytes} " +
                "A_e2e_med_ms=${a.e2eMsMedian} A_draw_med_us=${a.drawNsMedian / 1000} " +
                "A_handoff_med_us=${a.handoffNsMedian / 1000} " +
                "B_writes=${b.writes} B_alloc_B=${b.accountedAllocBytes} " +
                "B_e2e_med_ms=${b.e2eMsMedian} B_draw_med_us=${b.drawNsMedian / 1000} " +
                "B_handoff_med_us=${b.handoffNsMedian / 1000} " +
                "alloc_saved_B=$allocSaved handoff_delta_us=$handoffDeltaUs",
        )
    }

    private data class ArmStats(
        val width: Int,
        val height: Int,
        val accountedAllocBytes: Int,
        val writes: Int,
        val e2eMsMedian: Long,
        val drawNsMedian: Long,
        val handoffNsMedian: Long,
    )

    private fun timeArm(
        mode: IosCgImageTransferMode,
        surface: String,
        path: String,
        edge: Int,
    ): ArmStats {
        val e2e = ArrayList<Long>(6)
        val draw = ArrayList<Long>(6)
        val handoff = ArrayList<Long>(6)
        var width = 0
        var height = 0
        var alloc = 0
        var writes = 0
        repeat(6) { i ->
            IosCgImageTransferProbe.resetForTests()
            IosCgImageTransferProbe.mode = mode
            val mark = TimeSource.Monotonic.markNow()
            when (surface) {
                "compose" -> {
                    val bmp = IosImageIODecoder.decodeThumbnail(path, edge)
                    width = bmp.width
                    height = bmp.height
                }
                "bitmap" -> {
                    val bmp = IosImageIODecoder.decodeThumbnailBitmap(path, edge)
                    width = bmp.width
                    height = bmp.height
                }
                "image" -> {
                    val img = IosImageIODecoder.decodeThumbnailSkia(path, edge)
                    width = img.width
                    height = img.height
                }
                else -> error("unknown surface $surface")
            }
            val sample = IosCgImageTransferProbe.lastOrNull()
                ?: error("missing transfer sample")
            alloc = sample.accountedAllocBytes
            writes = sample.fullFrameWrites
            if (i > 0) {
                e2e += mark.elapsedNow().inWholeMilliseconds
                draw += sample.drawNs
                handoff += sample.handoffNs
            }
        }
        IosCgImageTransferProbe.resetForTests()
        return ArmStats(
            width = width,
            height = height,
            accountedAllocBytes = alloc,
            writes = writes,
            e2eMsMedian = median(e2e),
            drawNsMedian = median(draw),
            handoffNsMedian = median(handoff),
        )
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return -1L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun writeBusyPng(width: Int, height: Int): String? = runCatching {
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
                    center = Offset(
                        width * (i + 1) / 41f,
                        height * ((i * 7) % 40 + 1) / 41f,
                    ),
                )
            }
        }
        val bytes = IosWatermarkRenderer.encodePng(bmp)
        val path = NSTemporaryDirectory().trimEnd('/') +
            "/ewm-cg-ab-${NSUUID().UUIDString}.png"
        check(IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true))
        path
    }.getOrNull()

    private fun encodeHeicFromPath(pngPath: String): String? {
        val srcUrl = NSURL.fileURLWithPath(pngPath)
        val srcCf = CFBridgingRetain(srcUrl) ?: return null
        val source = try {
            @Suppress("UNCHECKED_CAST")
            CGImageSourceCreateWithURL(srcCf as CFURLRef, null)
        } finally {
            CFBridgingRelease(srcCf)
        } ?: return null
        val out = NSTemporaryDirectory().trimEnd('/') +
            "/ewm-cg-ab-${NSUUID().UUIDString}.heic"
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
