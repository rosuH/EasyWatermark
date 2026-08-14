@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.ui.image.IosProductThumbAbFixtures
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A/B for Phase-1 CGImage → Skia transfer.
 *
 * | Arm | Mode | Expected writes (Compose surface) |
 * |-----|------|-----------------------------------|
 * | A | [IosCgImageTransferMode.LegacyByteArray] | 3 (Draw + ByteArray→Skia memcpy + Compose re-raster) |
 * | B | [IosCgImageTransferMode.SkiaOwned] | 1 (Draw only) |
 *
 * Primary metric is [IosCgImageTransferSample.accountedAllocBytes] (research §1.2), not wall ms —
 * decode dominates; transfer is memory. Latency is reported for honesty.
 *
 * Prints `CG_TRANSFER_AB_*` lines (same style as `PRODUCT_THUMB_AB_*`).
 */
class IosCgImageTransferAbBenchTest {
    private val temporaryPaths = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        temporaryPaths.forEach(IosSourceStager::deleteQuietly)
        temporaryPaths.clear()
        IosCgImageTransferProbe.resetForTests()
        IosImageIOOwnershipProbe.resetForTests()
    }

    @Test
    fun png_composeSurface_abAcrossPreviewBuckets() {
        val path = writeBusyPng(2400, 1600)
        runComposeAb("png", path, srcW = 2400, srcH = 1600, edges = listOf(128, 720, 1920))
    }

    @Test
    fun heic_composeSurface_abAcrossPreviewBuckets() {
        val png = writeBusyPng(2400, 1600)
        val heic = IosProductThumbAbFixtures.encodeHeicFromPath(png)
        if (heic == null) {
            println("CG_TRANSFER_AB platform=ios fmt=heic SKIP encode_failed")
            return
        }
        temporaryPaths += heic
        runComposeAb("heic", heic, srcW = 2400, srcH = 1600, edges = listOf(128, 720, 1920))
    }

    @Test
    fun png_bitmapAndImageSurfaces_abAt1920() {
        val path = writeBusyPng(2400, 1600)
        for (surface in listOf("bitmap", "image", "compose")) {
            runSurfaceAb("png", path, edge = 1920, surface = surface)
        }
    }

    @Test
    fun pixels_matchBetweenArms_at720() {
        val path = writeBusyPng(800, 600)
        val a = IosCgImageTransferProbe.withMode(IosCgImageTransferMode.LegacyByteArray) {
            IosImageIODecoder.decodeThumbnail(path, 720)
        }
        val b = IosCgImageTransferProbe.withMode(IosCgImageTransferMode.SkiaOwned) {
            IosImageIODecoder.decodeThumbnail(path, 720)
        }
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertTrue(pixelsNearlyEqual(a, b), "A/B arms must agree on pixels")
    }

    private fun runComposeAb(fmt: String, path: String, srcW: Int, srcH: Int, edges: List<Int>) {
        for (edge in edges) {
            runSurfaceAb(fmt, path, edge, surface = "compose", srcW = srcW, srcH = srcH)
        }
    }

    private fun runSurfaceAb(
        fmt: String,
        path: String,
        edge: Int,
        surface: String,
        srcW: Int = 0,
        srcH: Int = 0,
    ) {
        // Order-balanced: A then B, then B then A on a second pair of cold runs — report medians
        // over the non-warmup samples for each arm.
        val a = timeArm(IosCgImageTransferMode.LegacyByteArray, surface, path, edge)
        val b = timeArm(IosCgImageTransferMode.SkiaOwned, surface, path, edge)
        val allocSaved = a.accountedAllocBytes - b.accountedAllocBytes
        val handoffDeltaUs = (a.handoffNsMedian - b.handoffNsMedian) / 1_000.0
        println(
            "CG_TRANSFER_AB platform=ios fmt=$fmt surface=$surface " +
                "src=${srcW}x$srcH edge=$edge out=${a.width}x${a.height} " +
                "A_writes=${a.writes} A_alloc_B=${a.accountedAllocBytes} " +
                "A_e2e_med_ms=${a.e2eMsMedian} A_draw_med_us=${a.drawNsMedian / 1000} " +
                "A_handoff_med_us=${a.handoffNsMedian / 1000} " +
                "B_writes=${b.writes} B_alloc_B=${b.accountedAllocBytes} " +
                "B_e2e_med_ms=${b.e2eMsMedian} B_draw_med_us=${b.drawNsMedian / 1000} " +
                "B_handoff_med_us=${b.handoffNsMedian / 1000} " +
                "alloc_saved_B=$allocSaved handoff_delta_us=${handoffDeltaUs.toLong()}",
        )
        assertEquals(
            if (surface == "compose") 3 else 2,
            a.writes,
            "Legacy arm writes for $surface",
        )
        assertEquals(1, b.writes, "Owned arm writes for $surface")
        assertTrue(
            b.accountedAllocBytes < a.accountedAllocBytes,
            "Owned arm must allocate fewer full-frame buffers",
        )
        assertEquals(a.frameBytes, b.frameBytes)
        assertEquals(a.accountedAllocBytes, a.frameBytes * a.writes)
        assertEquals(b.accountedAllocBytes, b.frameBytes * b.writes)
    }

    private data class ArmStats(
        val width: Int,
        val height: Int,
        val frameBytes: Int,
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
        var frameBytes = 0
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
                ?: error("missing transfer sample for $mode/$surface")
            frameBytes = sample.frameBytes
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
            frameBytes = frameBytes,
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

    private fun writeBusyPng(width: Int, height: Int): String {
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
        val bytes = SkiaImage.makeFromBitmap(bmp.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.PNG)!!.bytes
        val path = NSTemporaryDirectory().trimEnd('/') +
            "/ewm-cg-ab-${NSUUID().UUIDString}.png"
        assertTrue(IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true))
        temporaryPaths += path
        return path
    }

    private fun pixelsNearlyEqual(a: ImageBitmap, b: ImageBitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        for (y in 0 until a.height step 3) {
            for (x in 0 until a.width step 3) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                if (
                    abs(ca.red - cb.red) > 0.02f ||
                    abs(ca.green - cb.green) > 0.02f ||
                    abs(ca.blue - cb.blue) > 0.02f
                ) {
                    return false
                }
            }
        }
        return true
    }
}
