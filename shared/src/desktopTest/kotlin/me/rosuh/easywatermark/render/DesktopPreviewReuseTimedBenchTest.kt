package me.rosuh.easywatermark.render

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.WaterMark
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Timed Desktop Source-reuse bench. Prints a JSON line for the session report.
 */
class DesktopPreviewReuseTimedBenchTest {

    @Test
    fun timedReuse_secondPaintIsComposeOnly() = runBlocking {
        PreviewSourceReuseProbe.enabled = true
        PreviewSourceReuseProbe.reset()
        val dir = File("build/preview-source-reuse-bench").apply { mkdirs() }
        val file = File(dir, "bench-src.png")
        writePng(file, 2400, 1600)
        val bytes = file.readBytes()
        val repo = PreviewImageRepository<androidx.compose.ui.graphics.ImageBitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            approxBytes = { PreviewImageRepository.approxImageBitmapBytes(it) },
        )
        val bucket = 1440
        val srcKey = PreviewKey(file.absolutePath, bucket, PreviewPurpose.SourcePlaceholder)
        val wm = WaterMark.default

        val coldNs = measureNanoTime {
            val source = repo.load(srcKey) {
                DesktopPreviewRaster.decodeSourcePlaceholder(bytes, bucket)
            }!!
            DesktopPreviewRaster.renderWatermarked(
                imageBytes = ByteArray(0),
                waterMark = wm,
                offsetX = 0.5f,
                offsetY = 0.5f,
                maxEdgePx = bucket,
                background = source,
            )
        }
        val hotNs = measureNanoTime {
            val source = repo.load(srcKey) { error("hot path must not decode") }!!
            DesktopPreviewRaster.renderWatermarked(
                imageBytes = ByteArray(0),
                waterMark = wm.copy(alpha = 180),
                offsetX = 0.5f,
                offsetY = 0.5f,
                maxEdgePx = bucket,
                background = source,
            )
        }
        val snap = PreviewSourceReuseProbe.snapshot()
        val json = """
            {"platform":"desktop-jvm","sourcePx":"2400x1600","bucket":$bucket,
             "coldMs":${coldNs / 1_000_000.0},"hotMs":${hotNs / 1_000_000.0},
             "sourceDecodes":${snap.sourceDecodes},"composes":${snap.composes}}
        """.trimIndent().replace("\n", "")
        File(dir, "desktop-reuse-bench.json").writeText(json)
        println("PREVIEW_REUSE_BENCH $json")
        assertEquals(1, snap.sourceDecodes)
        assertTrue(snap.composes >= 2)
        assertTrue(hotNs < coldNs, "hot compose-only must be faster than cold decode+compose")
        repo.close()
        PreviewSourceReuseProbe.enabled = false
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(40, 72, 110)
        g.fillRect(0, 0, width, height)
        g.color = Color(220, 180, 60)
        g.fillRect(80, 80, width / 3, height / 3)
        g.dispose()
        ImageIO.write(img, "png", file)
    }
}
