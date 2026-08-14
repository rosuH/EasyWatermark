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
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S2: Image mode re-read the icon file and re-decoded it on **every** compose — one filmstrip tap
 * (focus + ±2) paid five file reads and five decodes, and so did every config change and every
 * CLAMP draft drag frame.
 */
@OptIn(ExperimentalForeignApi::class)
class IosWatermarkIconCacheTest {

    @Test
    fun manyComposes_decodeIconOnce() {
        IosWatermarkIconCache.resetForTests()
        val sourcePath = writePng(640, 480, Color(0xFF203040), "src")
        val iconPath = writePng(64, 48, Color.Red, "icon")
        val wm = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
        )

        // Focus + ±2 is five composes for one filmstrip tap.
        repeat(5) {
            IosPreviewRaster.renderWatermarked(
                sourcePath = sourcePath,
                waterMark = wm,
                maxEdgePx = 320,
            )
        }

        assertEquals(
            1,
            IosWatermarkIconCache.decodeCountForTests(),
            "five composes of one icon must cost one file read + decode",
        )
    }

    @Test
    fun differentIconRef_decodesAgain() {
        IosWatermarkIconCache.resetForTests()
        val first = MediaRef(writePng(64, 48, Color.Red, "icon"))
        val second = MediaRef(writePng(64, 48, Color.Blue, "icon"))

        IosWatermarkIconCache.decoded(first, IosPreviewRaster.ICON_MAX_EDGE_PX)
        IosWatermarkIconCache.decoded(first, IosPreviewRaster.ICON_MAX_EDGE_PX)
        assertEquals(1, IosWatermarkIconCache.decodeCountForTests(), "same ref must reuse")

        IosWatermarkIconCache.decoded(second, IosPreviewRaster.ICON_MAX_EDGE_PX)
        assertEquals(
            2,
            IosWatermarkIconCache.decodeCountForTests(),
            "a newly picked icon is a new MediaRef and must be decoded",
        )
    }

    @Test
    fun invalidate_forcesReDecode() {
        IosWatermarkIconCache.resetForTests()
        val ref = MediaRef(writePng(64, 48, Color.Green, "icon"))

        IosWatermarkIconCache.decoded(ref, IosPreviewRaster.ICON_MAX_EDGE_PX)
        IosWatermarkIconCache.invalidate()
        IosWatermarkIconCache.decoded(ref, IosPreviewRaster.ICON_MAX_EDGE_PX)

        assertEquals(
            2,
            IosWatermarkIconCache.decodeCountForTests(),
            "trimCaches/dispose must be able to drop the memo",
        )
    }

    @Test
    fun unreadableIcon_stillThrows() {
        IosWatermarkIconCache.resetForTests()
        val missing = MediaRef(
            NSTemporaryDirectory().trimEnd('/') + "/ewm_icon_absent_" + NSUUID().UUIDString(),
        )
        // The inline path propagated IosIconPersistence failure; caching must not turn an
        // unreadable icon into a silently watermark-less frame.
        assertFailsWith<IllegalStateException> {
            IosWatermarkIconCache.decoded(missing, IosPreviewRaster.ICON_MAX_EDGE_PX)
        }
    }

    private fun writePng(width: Int, height: Int, color: Color, tag: String): String {
        val path = NSTemporaryDirectory().trimEnd('/') +
            "/ewm_${tag}_" + NSUUID().UUIDString() + ".png"
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color)
        }
        val bytes = IosWatermarkRenderer.encodePng(bmp)
        assertTrue(
            IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true),
            "icon cache test fixture must be written",
        )
        return path
    }
}
