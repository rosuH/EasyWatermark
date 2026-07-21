package me.rosuh.easywatermark.session

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosFinalRenderSpine
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.render.IosRenderRequest
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adapter contract for [IosExportPipelinePort] (C3): validation + sole success E2E for
 * full-resolution JPEG prefs/offset/result mapping.
 */
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
     * Sole success E2E: 2048×1536 stays full-res; JPEG prefs → `.jpg` + magic; Port once.
     */
    @Test
    fun exportOne_jpeg_fullResolution_honorsPrefsOffset_andMapsResult() = runBlocking {
        val sourcePath = NSTemporaryDirectory() + "c3_source_" + NSUUID().UUIDString() + ".png"
        val sourceBytes = IosWatermarkRenderer.encodePng(solidBitmap(2048, 1536, Color(0xFF203040)))
        assertTrue(IosByteArrayInterop.toNSData(sourceBytes).writeToFile(sourcePath, atomically = true))
        val iconPath = NSTemporaryDirectory() + "c3_icon_" + NSUUID().UUIDString() + ".png"
        val iconBytes = IosWatermarkRenderer.encodePng(solidBitmap(48, 32, Color(0xFFFF0000)))
        assertTrue(IosByteArrayInterop.toNSData(iconBytes).writeToFile(iconPath, atomically = true))

        val offsetX = 0.17f
        val offsetY = 0.83f
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 14f,
            degree = 0f,
            alpha = 255,
        )
        val prefs = UserPreferences(ImageFormat.JPEG, 80)
        val imageInfo = ImageInfo(
            uri = MediaRef(sourcePath),
            width = 1,
            height = 1,
            offsetX = offsetX,
            offsetY = offsetY,
        )

        val spine = IosFinalRenderSpine.renderAndEncode(
            sourceBytes,
            IosRenderRequest(config, prefs, offsetX, offsetY),
            iconBytes = iconBytes,
        )

        val result = IosExportPipelinePort().exportOne(imageInfo, config, prefs)
        assertTrue(result.isSuccess(), "code=${result.code} msg=${result.message}")
        val outputPath = result.data!!.value
        assertTrue(outputPath.endsWith(".jpg"), "JPEG prefs must yield .jpg path")
        val outputData = NSData.dataWithContentsOfFile(outputPath)
        assertNotNull(outputData)
        val outputBytes = IosByteArrayInterop.fromNSData(outputData)
        assertTrue(
            outputBytes[0] == 0xFF.toByte() && outputBytes[1] == 0xD8.toByte() && outputBytes[2] == 0xFF.toByte(),
            "JPEG magic required",
        )
        assertEquals(2048, imageInfo.width)
        assertEquals(1536, imageInfo.height)
        assertEquals(2048, spine.width)
        assertEquals(1536, spine.height)
        // Same request through spine and Port → byte-identical product encode.
        assertContentEquals(spine.bytes, outputBytes)
    }

    /**
     * Issue 22 §2.5 steps 7–8: on atomic-write failure, [ImageInfo] width/height must remain
     * unchanged. Fail-closed: override must be invoked exactly once with non-empty JPEG payload
     * and a `.jpg` target path — so an earlier decode/render failure cannot pass this test.
     */
    @Test
    fun exportOne_failedWrite_doesNotMutateImageInfoDimensions() = runBlocking {
        val sourcePath = NSTemporaryDirectory() + "c3_fail_src_" + NSUUID().UUIDString() + ".png"
        val sourceBytes = IosWatermarkRenderer.encodePng(solidBitmap(64, 48, Color(0xFF203040)))
        assertTrue(IosByteArrayInterop.toNSData(sourceBytes).writeToFile(sourcePath, atomically = true))
        val iconPath = NSTemporaryDirectory() + "c3_fail_icon_" + NSUUID().UUIDString() + ".png"
        val iconBytes = IosWatermarkRenderer.encodePng(solidBitmap(16, 12, Color.Red))
        assertTrue(IosByteArrayInterop.toNSData(iconBytes).writeToFile(iconPath, atomically = true))

        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 12f,
            degree = 0f,
            alpha = 255,
        )
        val prefs = UserPreferences(ImageFormat.JPEG, 80)
        val imageInfo = ImageInfo(
            uri = MediaRef(sourcePath),
            width = 1,
            height = 1,
            offsetX = 0.2f,
            offsetY = 0.8f,
        )
        val port = IosExportPipelinePort()
        var writeCalls = 0
        var lastBytes: ByteArray? = null
        var lastPath: String? = null
        port.atomicWriteOverrideForTests = { bytes, path ->
            writeCalls += 1
            lastBytes = bytes
            lastPath = path
            false
        }
        try {
            val result = port.exportOne(imageInfo, config, prefs)
            assertTrue(result.isFailure(), "forced write failure must fail the export")
            assertEquals(
                1,
                writeCalls,
                "writer must be reached exactly once (fail-closed against early render failure)",
            )
            val payload = lastBytes
            assertNotNull(payload, "writer must receive encoded bytes")
            assertTrue(payload.isNotEmpty(), "writer payload must be non-empty")
            val target = lastPath
            assertNotNull(target, "writer must receive a target path")
            assertTrue(target.endsWith(".jpg"), "JPEG prefs must target .jpg path (got $target)")
            assertEquals(1, imageInfo.width, "width must not mutate when write fails")
            assertEquals(1, imageInfo.height, "height must not mutate when write fails")
        } finally {
            port.atomicWriteOverrideForTests = null
        }
    }

    private fun solidBitmap(width: Int, height: Int, color: Color): ImageBitmap {
        val bitmap = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) { drawRect(color) }
        return bitmap
    }
}
