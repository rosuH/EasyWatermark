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
    fun exportOne_commonRaster_succeedsWithNonEmptyMediaRef() = runBlocking {
        val src = File(app.cacheDir, "c2-export-src.png").apply {
            parentFile?.mkdirs()
            outputStream().use { out ->
                solidBg(96, 72).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        val uri = Uri.fromFile(src)
        val info = ImageInfo(
            uri = MediaRef(uri.toString()),
            width = 96,
            height = 72,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        val port = AndroidExportPipelinePort(appContext = app)
        val result = port.exportOne(
            imageInfo = info,
            config = WaterMark.default.copy(
                text = "C2Export",
                markMode = WatermarkMode.Text,
                degree = 315f,
            ),
            prefs = UserPreferences(ImageFormat.PNG, 100),
        )
        assertTrue(
            "exportOne must succeed under common raster (code=${result.code} msg=${result.message})",
            result.isSuccess(),
        )
        val outRef = result.data
        assertNotNull(outRef)
        assertTrue(outRef!!.value.isNotEmpty())
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
}
