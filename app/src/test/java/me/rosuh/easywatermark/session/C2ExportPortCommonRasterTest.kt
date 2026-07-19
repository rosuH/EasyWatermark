package me.rosuh.easywatermark.session

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.BaseHeight
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.BaseWidth
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.Quadrant
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.brightestQuadrant
import me.rosuh.easywatermark.utils.bitmap.AndroidExifTestFixture.jpegWithOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ADR-0018: shipped [AndroidExportPipelinePort.exportOne] always uses common compose
 * ([AndroidCommonRaster]) and returns a non-empty [MediaRef].
 *
 * Uses plain [Application] because the Android decode edge consumes its caller-provided resolver and
 * has no process-singleton dependency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C2ExportPortCommonRasterTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun exportOne_textClamp_nonCenter_pngReadback_currentContract() = runBlocking {
        val src = File(app.cacheDir, "c2-export-src.png").apply {
            parentFile?.mkdirs()
            outputStream().use { out ->
                solidBg(320, 240).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        val uri = Uri.fromFile(src)
        val port = AndroidExportPipelinePort(appContext = app)
        val config = WaterMark.default.copy(
            text = "C0.2",
            textSize = 32f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
        )

        val pngInfo = ImageInfo(
            uri = MediaRef(uri.toString()),
            width = 320,
            height = 240,
            offsetX = 0.17f,
            offsetY = 0.83f,
        )
        val result = port.exportOne(
            imageInfo = pngInfo,
            config = config,
            prefs = UserPreferences(ImageFormat.PNG, 90),
        )
        assertTrue(
            "PNG exportOne must succeed (code=${result.code} msg=${result.message})",
            result.isSuccess(),
        )
        val outputUri = Uri.parse(result.data!!.value)
        val pngBytes = app.contentResolver.openInputStream(outputUri).use { it!!.readBytes() }
        assertTrue(
            "PNG output must have canonical magic",
            pngBytes.take(8).toByteArray().contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
        val pngBitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        assertNotNull(pngBitmap)
        assertEquals(320, pngBitmap!!.width)
        assertEquals(240, pngBitmap.height)
        assertEquals(320, pngInfo.width)
        assertEquals(240, pngInfo.height)
        assertLocalizedLowerLeft(pngBitmap)
    }

    @Test
    fun exportOne_orientation7_writesUprightPixelsAndSwappedDimensions() = runBlocking {
        val source = File(app.cacheDir, "b3-export-orientation-7.jpg").apply {
            writeBytes(jpegWithOrientation(7))
        }
        val imageInfo = ImageInfo(
            uri = MediaRef(Uri.fromFile(source).toString()),
            width = BaseWidth,
            height = BaseHeight,
        )

        val result = AndroidExportPipelinePort(appContext = app).exportOne(
            imageInfo = imageInfo,
            config = WaterMark.default.copy(
                text = "B3",
                alpha = 0,
                markMode = WatermarkMode.Text,
            ),
            prefs = UserPreferences(ImageFormat.PNG, 100),
        )

        assertTrue(
            "orientation-7 export must succeed (code=${result.code} msg=${result.message})",
            result.isSuccess(),
        )
        assertEquals(BaseHeight, imageInfo.width)
        assertEquals(BaseWidth, imageInfo.height)
        val outputUri = Uri.parse(result.data!!.value)
        val outputBitmap = app.contentResolver.openInputStream(outputUri).use { input ->
            BitmapFactory.decodeStream(input)
        }
        assertNotNull(outputBitmap)
        assertEquals(BaseHeight, outputBitmap!!.width)
        assertEquals(BaseWidth, outputBitmap.height)
        assertEquals(Quadrant.BottomRight, brightestQuadrant(outputBitmap))
        val outputOrientation = app.contentResolver.openInputStream(outputUri).use { input ->
            ExifInterface(input!!).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        }
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, outputOrientation)
    }

    private fun solidBg(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun assertLocalizedLowerLeft(bitmap: Bitmap) {
        var changed = 0
        var minX = bitmap.width
        var maxX = -1
        var minY = bitmap.height
        var maxY = -1
        var sumX = 0L
        var sumY = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.BLUE) {
                    changed++
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                    sumX += x
                    sumY += y
                }
            }
        }
        assertTrue("CLAMP output must change pixels", changed > 0)
        val bboxW = maxX - minX + 1
        val bboxH = maxY - minY + 1
        assertTrue("CLAMP bbox must be localized horizontally (bboxW=$bboxW)", bboxW < bitmap.width * 0.60)
        assertTrue("CLAMP bbox must be localized vertically (bboxH=$bboxH)", bboxH < bitmap.height * 0.60)
        val centroidX = sumX.toDouble() / changed
        val centroidY = sumY.toDouble() / changed
        assertTrue("offsetX=0.17 must stay left of center (centroidX=$centroidX)", centroidX < bitmap.width * 0.45)
        assertTrue("offsetY=0.83 must stay below center (centroidY=$centroidY)", centroidY > bitmap.height * 0.65)
    }
}
