package me.rosuh.easywatermark.session

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.CommonRasterFlags
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * ADR-0018 / plan verification: shipped [AndroidExportPipelinePort.exportOne] with
 * [CommonRasterFlags.useCommonRasterExport] **on** must complete through the common compose path
 * (not a reimplemented painter in the test).
 *
 * Uses plain [Application] (not [MyApp]) to avoid Koin double-start; seeds [MyApp.instance] via
 * reflection so decode/EXIF helpers that read the process singleton still work under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C2ExportPortCommonRasterTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // BitmapUtils.decodeBitmapWithExifSync reads MyApp.instance for orientation helpers.
        ReflectionHelpers.setStaticField(MyApp::class.java, "instance", app as Context)
        CommonRasterFlags.useCommonRasterExport = true
        CommonRasterFlags.useCommonRasterPreview = true
    }

    @Test
    fun exportOne_commonFlagOn_succeedsWithNonEmptyMediaRef() = runBlocking {
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
            "exportOne must succeed under common raster flag (code=${result.code} msg=${result.message})",
            result.isSuccess(),
        )
        val outRef = result.data
        assertNotNull(outRef)
        assertTrue(outRef!!.value.isNotEmpty())
    }

    private fun solidBg(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
}
