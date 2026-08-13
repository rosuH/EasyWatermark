package me.rosuh.easywatermark.render

import me.rosuh.easywatermark.data.model.WaterMark
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wall-clock cost of one **cold** watermarked preview paint through the shipped
 * [DesktopPreviewRaster] path (same CommonWatermarkPipeline as iOS [IosPreviewRaster]).
 *
 * Used to quantify "switch image feels slow" when cache misses: at 50 images the iOS
 * watermarked cache holds ≪50 frames, so most non-neighbor switches pay this full cost.
 *
 * Numbers are host-JVM (not iPhone), but order-of-magnitude and scale-with-edge are real.
 */
class SwitchPreviewRasterCostTest {

    @Test
    fun coldWatermarkedPreview_recordsDecodeComposeMs_forCacheMissModel() {
        val png720 = writeJpegLikePng(2400, 1800) // large source, downscale to maxEdge
        try {
            val wm = WaterMark.default
            // Warm JIT once (not counted).
            DesktopPreviewRaster.renderWatermarkedFile(
                sourcePath = png720.absolutePath,
                waterMark = wm,
                offsetX = 0.5f,
                offsetY = 0.5f,
                maxEdgePx = 720,
            )

            val samples720 = LongArray(5) {
                measureNanoTime {
                    DesktopPreviewRaster.renderWatermarkedFile(
                        sourcePath = png720.absolutePath,
                        waterMark = wm,
                        offsetX = 0.5f,
                        offsetY = 0.5f,
                        maxEdgePx = 720,
                    )
                } / 1_000_000
            }
            val samples1080 = LongArray(5) {
                measureNanoTime {
                    DesktopPreviewRaster.renderWatermarkedFile(
                        sourcePath = png720.absolutePath,
                        waterMark = wm,
                        offsetX = 0.5f,
                        offsetY = 0.5f,
                        maxEdgePx = 1080,
                    )
                } / 1_000_000
            }
            val med720 = samples720.sorted()[2]
            val med1080 = samples1080.sorted()[2]
            val min720 = samples720.min()
            val max720 = samples720.max()

            // Evidence line for diagnosis report (also printed to stdout for CI logs).
            // Desktop PNG is a lower bound; iOS HEIC/ImageIO + Main publish is typically higher
            // (see Instruments jank-repro-20260808 hangs mean ~461ms on settle path).
            println(
                "SWITCH_PREVIEW_COST " +
                    "edge720_ms=${samples720.joinToString(",")} med=$med720 " +
                    "edge1080_ms=${samples1080.joinToString(",")} med=$med1080 " +
                    "source=${png720.name}",
            )

            // Floor: real decode+compose must be measurable (not a no-op stub).
            assertTrue(min720 >= 1L, "cold 720 paint too fast to be real work: ${min720}ms")
            // Sanity upper bound so a hang doesn't hang the suite forever (already finished).
            assertTrue(max720 < 30_000L, "cold 720 paint pathologically slow: ${max720}ms")
            // Must exceed one display frame on this host so miss cost is non-trivial vs hard-cut 0ms.
            assertTrue(
                med720 >= 8L,
                "cold 720 med=${med720}ms should exceed ~0.5 frame (16ms budget) lower bound 8ms",
            )
            assertTrue(med1080 >= 1L, "1080 paint must perform work (med=${med1080}ms)")
        } finally {
            png720.delete()
        }
    }

    private fun writeJpegLikePng(width: Int, height: Int): File {
        val surface = Surface.makeRasterN32Premul(width, height)
        // Non-solid fill so decoder/downsample has real work.
        val canvas = surface.canvas
        canvas.clear(0xFF224466.toInt())
        for (i in 0 until 40) {
            canvas.drawCircle(
                (width * (i + 1) / 41f),
                (height * ((i * 7) % 40 + 1) / 41f),
                width / 30f,
                org.jetbrains.skia.Paint().apply { color = 0xFF88AA33.toInt() },
            )
        }
        val image = surface.makeImageSnapshot()
        val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
        val file = File.createTempFile("ewm-switch-cost-", ".png")
        file.writeBytes(data.bytes)
        image.close()
        surface.close()
        return file
    }
}
