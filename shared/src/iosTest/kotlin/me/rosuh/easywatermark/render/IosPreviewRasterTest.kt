package me.rosuh.easywatermark.render

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Preview budget + in-memory/no-export-file + common paint (C3).
 */
@OptIn(ExperimentalForeignApi::class)
class IosPreviewRasterTest {

    @Test
    fun preview_keeps720Budget_usesOffset_and_writesNoExportFile() {
        val dirMarker = NSTemporaryDirectory()
        // Mutation-resistant enumerator: a real ewm_out_* sentinel must appear in the listing.
        val sentinelName = "ewm_out_sentinel_" + NSUUID().UUIDString() + ".png"
        val sentinelPath = dirMarker + sentinelName
        val sentinelBytes = IosWatermarkRenderer.encodePng(solid(8, 8, Color.Green))
        assertTrue(IosByteArrayInterop.toNSData(sentinelBytes).writeToFile(sentinelPath, atomically = true))
        val listedWithSentinel = listEwmOut(dirMarker)
        assertTrue(
            listedWithSentinel.contains(sentinelName),
            "listEwmOut must see real ewm_out_* files (got $listedWithSentinel)",
        )

        val before = listEwmOut(dirMarker)

        val sourcePath = dirMarker + "c3_prev_src_" + NSUUID().UUIDString() + ".png"
        val sourceBytes = IosWatermarkRenderer.encodePng(solid(2048, 1536, Color(0xFF203040)))
        assertTrue(IosByteArrayInterop.toNSData(sourceBytes).writeToFile(sourcePath, atomically = true))

        val iconPath = dirMarker + "c3_prev_icon_" + NSUUID().UUIDString() + ".png"
        val iconBytes = IosWatermarkRenderer.encodePng(solid(32, 24, Color.Red))
        assertTrue(IosByteArrayInterop.toNSData(iconBytes).writeToFile(iconPath, atomically = true))

        val wm = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 14f,
            degree = 0f,
            alpha = 255,
        )
        val preview = IosPreviewRaster.renderWatermarked(
            sourcePath = sourcePath,
            waterMark = wm,
            offsetX = 0.17f,
            offsetY = 0.83f,
        )
        assertEquals(720, preview.width)
        assertEquals(540, preview.height)

        // No new ewm_out_* product export files from Preview (real Foundation directory enumeration).
        val after = listEwmOut(dirMarker)
        assertTrue(
            after.subtract(before).isEmpty(),
            "Preview must not create ewm_out_* files; new=${after.subtract(before)}",
        )

        // Common-pipeline paint: same thumbnail background + pipeline at same offset should match.
        val thumb = IosImageDecoder.decodeThumbnail(sourceBytes, maxEdgePx = 720)
        val iconThumb = IosImageDecoder.decodeThumbnail(iconBytes, maxEdgePx = 256)
        val viaPipeline = CommonWatermarkPipeline.compose(
            background = thumb,
            config = wm,
            env = IosTextRasterEnv.textRasterEnv(),
            icon = iconThumb,
            offsetX = 0.17f,
            offsetY = 0.83f,
            fontFamily = null,
        )
        assertTrue(bitmapsNearlyEqual(preview, viaPipeline), "Preview must match common pipeline paint")
    }

    @Test
    fun injectedBackground_secondRender_doesNotOpenImageIO() {
        val dir = NSTemporaryDirectory()
        val sourcePath = dir + "c3_reuse_src_" + NSUUID().UUIDString() + ".png"
        val sourceBytes = IosWatermarkRenderer.encodePng(solid(640, 480, Color(0xFF405060)))
        assertTrue(IosByteArrayInterop.toNSData(sourceBytes).writeToFile(sourcePath, atomically = true))

        IosImageIOOwnershipProbe.resetForTests()
        IosDecodePurposeProbe.resetForTests()
        val background = IosImageIODecoder.decodeThumbnail(sourcePath, 720)
        val sourcesAfterDecode = IosImageIOOwnershipProbe.snapshotForTests().sourcesCreated
        assertTrue(sourcesAfterDecode >= 1)
        IosDecodePurposeProbe.resetForTests()

        val wm = WaterMark.default.copy(
            markMode = WatermarkMode.Text,
            text = "reuse",
            textSize = 14f,
            degree = 0f,
            alpha = 255,
        )
        val first = IosPreviewRaster.renderWatermarked(
            sourcePath = sourcePath,
            waterMark = wm,
            background = background,
        )
        val second = IosPreviewRaster.renderWatermarked(
            sourcePath = sourcePath,
            waterMark = wm.copy(alpha = 180),
            background = background,
        )
        assertEquals(background.width, first.width)
        assertEquals(background.height, second.height)
        assertEquals(
            0,
            IosDecodePurposeProbe.snapshotForTests().watermarkedPreview,
            "injected background must not ImageIO-decode inside renderWatermarked",
        )
        assertEquals(
            sourcesAfterDecode,
            IosImageIOOwnershipProbe.snapshotForTests().sourcesCreated,
            "config change on an injected source must not open another CGImageSource",
        )
    }

    /** Real temp-dir listing of `ewm_out_*` basenames via [NSFileManager]. Fail-loud if unreadable. */
    private fun listEwmOut(tmp: String): Set<String> {
        val path = tmp.trimEnd('/')
        @Suppress("UNCHECKED_CAST")
        val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null)
            as? List<*>
            ?: error("listEwmOut: contentsOfDirectoryAtPath failed for $path")
        return contents.mapNotNull { it as? String }
            .filter { it.startsWith("ewm_out_") }
            .toSet()
    }

    private fun solid(w: Int, h: Int, color: Color): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return bmp
    }

    private fun bitmapsNearlyEqual(a: ImageBitmap, b: ImageBitmap, eps: Float = 0.02f): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        for (y in 0 until a.height step 4) {
            for (x in 0 until a.width step 4) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                if (abs(ca.red - cb.red) > eps ||
                    abs(ca.green - cb.green) > eps ||
                    abs(ca.blue - cb.blue) > eps ||
                    abs(ca.alpha - cb.alpha) > eps
                ) {
                    return false
                }
            }
        }
        return true
    }
}
