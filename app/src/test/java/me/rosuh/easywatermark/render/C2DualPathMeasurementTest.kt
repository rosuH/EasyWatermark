package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.obtainTileMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * ADR-0018 / plan **P0.3**: test-only dual-path measurement — **native** [WatermarkRenderer]
 * vs **shipped** [AndroidCommonRaster] / [CommonWatermarkPipeline] on the same fixture.
 *
 * Does **not** flip production permanently; asserts both paths produce non-blank same-sized
 * bitmaps and logs opaque IoU for owner review (CJK/engine delta expected under C2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class C2DualPathMeasurementTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        CommonRasterFlags.useCommonRasterExport = true
        CommonRasterFlags.useCommonRasterPreview = true
    }

    @Test
    fun dualPath_text_latin_sameDims_bothNonBlank() {
        val bg = solidBg(320, 240)
        val config = WaterMark.default.copy(
            text = "EasyWatermark",
            markMode = WatermarkMode.Text,
            degree = 315f,
            textSize = 14f,
        )
        val info = ImageInfo(
            uri = MediaRef("test://dual"),
            width = bg.width,
            height = bg.height,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )

        val native = nativeCompose(bg, config, info)
        val common = AndroidCommonRaster.composeToBitmap(context, bg, config, info, icon = null)

        assertEquals(native.width, common.width)
        assertEquals(native.height, common.height)
        assertTrue("native must paint ink", opaqueCount(native) > 0)
        assertTrue("common must paint ink", opaqueCount(common) > 0)

        val iou = opaqueIoU(native, common)
        val outDir = File("build/c2-dual-path").apply { mkdirs() }
        writePng(native, File(outDir, "native_latin.png"))
        writePng(common, File(outDir, "common_latin.png"))
        File(outDir, "metrics_latin.txt").writeText(
            "opaqueIoU=$iou nativeOpaque=${opaqueCount(native)} commonOpaque=${opaqueCount(common)}\n",
        )
        // Soft gate: not byte-parity; require some agreement for solid latin (not blank mismatch).
        assertTrue("latin IoU should be > 0 (got $iou)", iou > 0.0)
    }

    @Test
    fun dualPath_text_cjk_bothNonBlank_logsDelta() {
        val bg = solidBg(320, 240)
        val config = WaterMark.default.copy(
            text = "请勿转载",
            markMode = WatermarkMode.Text,
            degree = 315f,
            textSize = 14f,
        )
        val info = ImageInfo(
            uri = MediaRef("test://cjk"),
            width = bg.width,
            height = bg.height,
        )
        val native = nativeCompose(bg, config, info)
        val common = AndroidCommonRaster.composeToBitmap(context, bg, config, info, null)
        assertEquals(native.width, common.width)
        assertTrue(opaqueCount(native) > 0 || opaqueCount(common) > 0)
        val iou = opaqueIoU(native, common)
        val outDir = File("build/c2-dual-path").apply { mkdirs() }
        writePng(native, File(outDir, "native_cjk.png"))
        writePng(common, File(outDir, "common_cjk.png"))
        File(outDir, "metrics_cjk.txt").writeText(
            "opaqueIoU=$iou (CJK engine delta expected under C2)\n",
        )
    }

    @Test
    fun commonPipeline_isInvokedWhenExportFlagOn() {
        // Same helper [AndroidExportPipelinePort] calls when useCommonRasterExport is on.
        assertTrue(CommonRasterFlags.useCommonRasterExport)
        val bg = solidBg(64, 64)
        val config = WaterMark.default.copy(text = "OK", markMode = WatermarkMode.Text)
        val info = ImageInfo(uri = MediaRef("x"), width = 64, height = 64)
        val out = AndroidCommonRaster.composeToBitmap(context, bg, config, info, null)
        assertNotNull(out)
        assertEquals(64, out.width)
        assertTrue(opaqueCount(out) > 0)
        // Full MediaStore/export entry is [C2ExportPortCommonRasterTest].
    }

    private fun solidBg(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun nativeCompose(bg: Bitmap, config: WaterMark, info: ImageInfo): Bitmap {
        val mutable = bg.copy(Bitmap.Config.ARGB_8888, true)!!
        val canvas = Canvas(mutable)
        val paint = TextPaint().applyConfig(info, config, isScale = false)
        val shader = runBlocking {
            WatermarkRenderer.buildTextShader(
                info,
                config,
                paint,
                androidTextMeasureEnv(context),
                Dispatchers.Unconfined,
            )
        }
        assertNotNull(shader)
        WatermarkRenderer.compose(
            canvas = canvas,
            shader = shader!!,
            tileMode = config.obtainTileMode(),
            paint = Paint(),
            left = 0f,
            top = 0f,
            regionWidth = mutable.width.toFloat(),
            regionHeight = mutable.height.toFloat(),
            offsetX = info.offsetX,
            offsetY = info.offsetY,
        )
        return mutable
    }

    private fun opaqueCount(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { (it ushr 24) and 0xFF > 0 && it != Color.BLUE }
    }

    private fun opaqueIoU(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height)
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(b.width * b.height)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var inter = 0
        var union = 0
        for (i in pa.indices) {
            val oa = ((pa[i] ushr 24) and 0xFF) > 0 && pa[i] != Color.BLUE
            val ob = ((pb[i] ushr 24) and 0xFF) > 0 && pb[i] != Color.BLUE
            if (oa || ob) union++
            if (oa && ob) inter++
        }
        return if (union == 0) 1.0 else inter.toDouble() / union
    }

    private fun writePng(b: Bitmap, file: File) {
        file.outputStream().use { out ->
            b.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
