package me.rosuh.easywatermark.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C3.5 runtime witness: Q+ production [AndroidExportPipelinePort] publishes canonical
 * `image/jpeg` + `.jpg` + JPEG magic. Deletes only its own MediaStore output in [finally].
 */
@RunWith(AndroidJUnit4::class)
class AndroidExportPipelineMimeInstrumentedTest {

    @Test
    fun exportOne_jpeg_mediaStoreRow_isCanonicalMimeAndJpg() = runBlocking {
        assumeTrue(
            "C3.5 MediaStore MIME witness requires API 29+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "c35_mime_src.png").apply {
            parentFile?.mkdirs()
            outputStream().use { out ->
                Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(0x20, 0x30, 0x40))
                }.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        val imageInfo = ImageInfo(
            uri = MediaRef(Uri.fromFile(source).toString()),
            width = 1,
            height = 1,
        )
        val port = AndroidExportPipelinePort(appContext = context)
        var outputUri: Uri? = null
        try {
            val result = port.exportOne(
                imageInfo = imageInfo,
                config = WaterMark.default.copy(
                    text = "C35",
                    alpha = 0,
                    markMode = WatermarkMode.Text,
                ),
                prefs = UserPreferences(ImageFormat.JPEG, 85),
            )
            assertTrue(
                "exportOne must succeed " +
                    "(code=${(result as? ExportOutcome.Failure)?.failure?.legacyCode} " +
                    "msg=${(result as? ExportOutcome.Failure)?.failure?.message})",
                result.isSuccess(),
            )
            outputUri = Uri.parse((result as ExportOutcome.Success).media.ref.value)
            val projection = arrayOf(
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DISPLAY_NAME,
            )
            context.contentResolver.query(outputUri!!, projection, null, null, null).use { cursor ->
                assertNotNull(cursor)
                assertTrue(cursor!!.moveToFirst())
                val mime = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE),
                )
                val name = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME),
                )
                assertEquals("image/jpeg", mime)
                assertTrue("display name must end with .jpg (got $name)", name.endsWith(".jpg"))
            }
            val bytes = context.contentResolver.openInputStream(outputUri).use { it!!.readBytes() }
            assertTrue(
                bytes.size >= 3 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte(),
            )
            // Encoded output dimensions (not only ImageInfo, which Port sets from decode before encode).
            val outputBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(outputBitmap)
            assertEquals(64, outputBitmap!!.width)
            assertEquals(48, outputBitmap.height)
            assertEquals(64, imageInfo.width)
            assertEquals(48, imageInfo.height)
        } finally {
            outputUri?.let { context.contentResolver.delete(it, null, null) }
        }
    }
}
