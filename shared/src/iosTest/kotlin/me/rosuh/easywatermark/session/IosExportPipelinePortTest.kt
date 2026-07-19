package me.rosuh.easywatermark.session

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class IosExportPipelinePortTest {

    @Test
    fun exportOne_missingSource_fails() = runBlocking {
        val port = IosExportPipelinePort()
        val result = port.exportOne(
            ImageInfo(MediaRef("/tmp/ewm_does_not_exist_phase4.png")),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(result.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
    }

    /**
     * CURRENT contract only: iOS Port ignores JPEG prefs, writes PNG, and applies the preview
     * max-edge budget to export. C3 replaces this with the target full-resolution format contract.
     */
    @Test
    fun exportOne_currentContract_pngMagic_and_downscales2048x1536To1080x810() = runBlocking {
        val sourcePath = NSTemporaryDirectory() + "c0_2_source_" + NSUUID().UUIDString() + ".png"
        val sourceBytes = IosWatermarkRenderer.encodePng(largeBackground())
        assertTrue(IosByteArrayInterop.toNSData(sourceBytes).writeToFile(sourcePath, atomically = true))
        val iconPath = NSTemporaryDirectory() + "c0_2_icon_" + NSUUID().UUIDString() + ".png"
        val iconBytes = IosWatermarkRenderer.encodePng(solidBitmap(32, 24, Color(0xFFFFB800)))
        assertTrue(IosByteArrayInterop.toNSData(iconBytes).writeToFile(iconPath, atomically = true))
        val imageInfo = ImageInfo(
            uri = MediaRef(sourcePath),
            width = 2048,
            height = 1536,
        )

        val result = IosExportPipelinePort().exportOne(
            imageInfo = imageInfo,
            config = WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = MediaRef(iconPath),
            ),
            prefs = UserPreferences(ImageFormat.JPEG, 20),
        )

        assertTrue(
            result.isSuccess(),
            "current iOS Port export must succeed (code=${result.code} msg=${result.message})",
        )
        val outputPath = result.data!!.value
        assertTrue(outputPath.endsWith(".png"), "current iOS Port output path must remain .png")
        val outputData = NSData.dataWithContentsOfFile(outputPath)
        assertNotNull(outputData)
        val outputBytes = IosByteArrayInterop.fromNSData(outputData)
        assertTrue(
            outputBytes.take(8).toByteArray().contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
            "current iOS Port output must have PNG magic even when JPEG is requested",
        )
        val decoded = IosImageDecoder.decode(outputBytes)
        assertEquals(1080, decoded.width)
        assertEquals(810, decoded.height)
        assertEquals(1080, imageInfo.width)
        assertEquals(810, imageInfo.height)
    }

    private fun largeBackground(): ImageBitmap {
        return solidBitmap(2048, 1536, Color(0xFF203040))
    }

    private fun solidBitmap(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) {
            drawRect(color)
        }
        return bitmap
    }
}
