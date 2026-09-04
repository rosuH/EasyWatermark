package me.rosuh.easywatermark.session

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * H2: production-seam proof that [AndroidExportPipelinePort] early-releases owned
 * source (after compose) and composed (after encode) intermediates.
 *
 * Does **not** recycle BitmapCache-owned icon bitmaps (Image mode uses cache path).
 * Export resolution/quality unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidExportIntermediateReleaseTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun solidPngFile(name: String, w: Int = 48, h: Int = 36): File {
        val f = File(app.cacheDir, name)
        f.parentFile?.mkdirs()
        f.outputStream().use { out ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.rgb(0x20, 0x30, 0x40))
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
        }
        return f
    }

    @Test
    fun exportOne_textPng_releasesSourceAfterCompose_andComposedAfterEncode() = runBlocking {
        AndroidExportMemoryProbe.reset()
        val src = solidPngFile("h2-export-src.png")
        val port = AndroidExportPipelinePort(appContext = app)
        val info = ImageInfo(
            uri = MediaRef(Uri.fromFile(src).toString()),
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        val config = WaterMark.default.copy(
            markMode = WatermarkMode.Text,
            text = "H2",
            tileMode = WatermarkTileMode.CLAMP,
            textSize = 24f,
            alpha = 200,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val result = port.exportOne(info, config, prefs)
        assertTrue("export must succeed: $result", result is ExportOutcome.Success)
        assertTrue(
            "source must be released after compose (probe=${AndroidExportMemoryProbe.sourceReleasedAfterComposeCount})",
            AndroidExportMemoryProbe.sourceReleasedAfterComposeCount >= 1,
        )
        assertTrue(
            "composed must be released after encode (probe=${AndroidExportMemoryProbe.composedReleasedAfterEncodeCount})",
            AndroidExportMemoryProbe.composedReleasedAfterEncodeCount >= 1,
        )
        Unit
    }

    @Test
    fun exportOne_jpeg_stillReleasesIntermediates_andKeepsFullRes() = runBlocking {
        AndroidExportMemoryProbe.reset()
        val src = solidPngFile("h2-jpeg-src.png", w = 96, h = 64)
        val port = AndroidExportPipelinePort(appContext = app)
        val info = ImageInfo(
            uri = MediaRef(Uri.fromFile(src).toString()),
            offsetX = 0.2f,
            offsetY = 0.8f,
        )
        val config = WaterMark.default.copy(
            text = "H2J",
            tileMode = WatermarkTileMode.REPEAT,
            textSize = 20f,
        )
        val prefs = UserPreferences(ImageFormat.JPEG, 85)
        val result = port.exportOne(info, config, prefs)
        assertTrue(result is ExportOutcome.Success)
        val success = result as ExportOutcome.Success
        // Full-res: JPEG path must report source-sized dims (no hidden downscale).
        assertTrue(success.media.width >= 90)
        assertTrue(success.media.height >= 60)
        assertTrue(AndroidExportMemoryProbe.sourceReleasedAfterComposeCount >= 1)
        assertTrue(AndroidExportMemoryProbe.composedReleasedAfterEncodeCount >= 1)
        Unit
    }
}
