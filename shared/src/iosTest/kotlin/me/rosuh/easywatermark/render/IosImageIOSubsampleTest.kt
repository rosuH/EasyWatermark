package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S3: `kCGImageSourceCreateThumbnailFromImageAlways` decodes the full image and then scales, so a
 * small thumbnail is not cheap. `kCGImageSourceSubsampleFactor` moves the reduction into the
 * decoder — but only safely if the factor is derived from the source's own long edge.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImageIOSubsampleTest {

    @Test
    fun factor_neverSubsamplesBelowTheRequestedEdge() {
        // 4032 long edge (12MP camera HEIC) at the filmstrip thumb edge: /8 is still 504 ≥ 128.
        assertEquals(8, IosImageIODecoder.subsampleFactorFor(4032, 128))
        // At the phone preview 长边, /2 is 2016 ≥ 1920 but /4 would be 1008 — must pick 2.
        assertEquals(2, IosImageIODecoder.subsampleFactorFor(4032, 1920))
        // A source barely above the request cannot be reduced at all.
        assertEquals(IosImageIODecoder.NO_SUBSAMPLE, IosImageIODecoder.subsampleFactorFor(2000, 1920))
        assertEquals(IosImageIODecoder.NO_SUBSAMPLE, IosImageIODecoder.subsampleFactorFor(128, 128))
    }

    @Test
    fun factor_isAlwaysOneOfTheDocumentedValues() {
        val allowed = setOf(IosImageIODecoder.NO_SUBSAMPLE, 2, 4, 8)
        for (sourceEdge in listOf(0, 1, 127, 128, 256, 512, 1920, 3024, 4032, 8064, 12000)) {
            for (requested in listOf(1, 64, 96, 128, 192, 512, 720, 1920, 3840)) {
                val factor = IosImageIODecoder.subsampleFactorFor(sourceEdge, requested)
                assertTrue(
                    factor in allowed,
                    "kCGImageSourceSubsampleFactor only accepts 2/4/8 " +
                        "(source=$sourceEdge requested=$requested got=$factor)",
                )
                if (factor > IosImageIODecoder.NO_SUBSAMPLE) {
                    assertTrue(
                        sourceEdge / factor >= requested,
                        "subsampled edge ${sourceEdge / factor} must not fall below $requested",
                    )
                }
            }
        }
    }

    @Test
    fun subsampledThumbnail_hasTheSameOutputSizeAsTheFullDecodePath() {
        val path = writePng(1024, 768)

        for (edge in listOf(96, 128, 192, 512)) {
            val plain = IosImageIODecoder.decodeThumbnail(path, edge)
            val subsampled = IosImageIODecoder.decodeThumbnail(path, edge, allowSubsample = true)
            assertEquals(
                plain.width to plain.height,
                subsampled.width to subsampled.height,
                "subsampling must not change output dimensions at $edge px",
            )
            assertEquals(
                edge,
                maxOf(subsampled.width, subsampled.height),
                "requested long edge must still be honored at $edge px",
            )
        }
    }

    private fun writePng(width: Int, height: Int): String {
        val path = NSTemporaryDirectory().trimEnd('/') +
            "/ewm_sub_src_" + NSUUID().UUIDString() + ".png"
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(Color(0xFF1E3A5F))
            // Non-flat content so a resampling difference would be visible if one appeared.
            for (i in 0 until 24) {
                drawCircle(
                    color = Color(0xFFE0B040),
                    radius = width / 48f,
                    center = androidx.compose.ui.geometry.Offset(
                        width * (i + 1) / 25f,
                        height * ((i * 5) % 24 + 1) / 25f,
                    ),
                )
            }
        }
        val bytes = IosWatermarkRenderer.encodePng(bmp)
        assertTrue(
            IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true),
            "subsample test fixture must be written",
        )
        return path
    }
}
