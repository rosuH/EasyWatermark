package me.rosuh.easywatermark.session

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
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
 * ([AndroidCommonRaster]) and returns typed [ExportOutcome] with [ExportedMedia] (D1).
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
        val media = requireSuccessMedia(result)
        assertEquals(ImageFormat.PNG, media.format)
        assertEquals(320, media.width)
        assertEquals(240, media.height)
        assertTrue("PNG byteCount must be positive", media.byteCount > 0)
        val outputUri = Uri.parse(media.ref.value)
        val (mime, displayName) = queryMimeAndDisplayName(outputUri)
        assertEquals("image/png", mime)
        assertTrue("PNG display name must end with .png (got $displayName)", displayName!!.endsWith(".png"))
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

    /**
     * C3.5: Q+ JPEG MediaStore row must use canonical `image/jpeg` (not historical `image/jpg`),
     * keep `.jpg` display name, JPEG magic, orientation-7 upright dims/quadrant, stripped EXIF.
     * Queries the returned MediaStore URI — mapper-only / source-grep is not Port evidence.
     */
    @Test
    fun exportOne_jpeg_usesCanonicalMime_jpgMagic_andUprightDimensions() = runBlocking {
        val source = File(app.cacheDir, "c35-export-orientation-7.jpg").apply {
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
                text = "C3.5",
                alpha = 0,
                markMode = WatermarkMode.Text,
            ),
            prefs = UserPreferences(ImageFormat.JPEG, 90),
        )
        val media = requireSuccessMedia(result)
        assertEquals(ImageFormat.JPEG, media.format)
        assertTrue("JPEG byteCount must be positive", media.byteCount > 0)
        val outputUri = Uri.parse(media.ref.value)
        val (mime, displayName) = queryMimeAndDisplayName(outputUri)
        assertEquals("image/jpeg", mime)
        assertTrue(
            "JPEG display name must end with .jpg (got $displayName)",
            displayName!!.endsWith(".jpg"),
        )
        val jpegBytes = app.contentResolver.openInputStream(outputUri).use { it!!.readBytes() }
        assertTrue(
            "JPEG magic FF D8 FF required",
            jpegBytes.size >= 3 &&
                jpegBytes[0] == 0xFF.toByte() &&
                jpegBytes[1] == 0xD8.toByte() &&
                jpegBytes[2] == 0xFF.toByte(),
        )
        val outputBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        assertNotNull(outputBitmap)
        assertEquals(BaseHeight, outputBitmap!!.width)
        assertEquals(BaseWidth, outputBitmap.height)
        assertEquals(Quadrant.BottomRight, brightestQuadrant(outputBitmap))
        assertEquals(BaseHeight, imageInfo.width)
        assertEquals(BaseWidth, imageInfo.height)
        val outputOrientation = app.contentResolver.openInputStream(outputUri).use { input ->
            ExifInterface(input!!).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        }
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, outputOrientation)
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

        val media = requireSuccessMedia(result)
        assertEquals(BaseHeight, imageInfo.width)
        assertEquals(BaseWidth, imageInfo.height)
        assertEquals(BaseHeight, media.width)
        assertEquals(BaseWidth, media.height)
        val outputUri = Uri.parse(media.ref.value)
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

    // ─── C4.1 production-Port sentinels (issue 24 §4) ─────────────────────

    @Test
    fun exportOne_textCjkMultilineRepeat_partialAlpha_isBroad() = runBlocking {
        val bg = paintBg(320, 240)
        val src = writePng("c41-text-repeat-bg.png", bg)
        val config = WaterMark.default.copy(
            text = "请勿转载\n第二行",
            textSize = 28f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            alpha = 128,
            hGap = 0,
            vGap = 0,
        )
        val out = exportPng(src, config, 0.5f, 0.5f)
        val stats = deltaVsBg(bg, out)
        assertTrue("CJK multiline REPEAT must paint ink", stats.changedCount > 50)
        assertTrue("REPEAT bboxW broad", stats.bboxW >= (0.55 * out.width).toInt())
        assertTrue("REPEAT bboxH broad", stats.bboxH >= (0.55 * out.height).toInt())
    }

    @Test
    fun exportOne_textMultilineClamp_offsetPair_movesCentroid() = runBlocking {
        val bg = paintBg(320, 240)
        val src = writePng("c41-text-clamp-bg.png", bg)
        val config = WaterMark.default.copy(
            text = "LineA\nLineB",
            textSize = 28f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            alpha = 255,
            hGap = 0,
            vGap = 0,
        )
        val a = exportPng(src, config, 0.17f, 0.83f)
        val b = exportPng(src, config, 0.83f, 0.17f)
        val sa = deltaVsBg(bg, a)
        val sb = deltaVsBg(bg, b)
        assertTrue("both offsets must paint", sa.changedCount > 0 && sb.changedCount > 0)
        val dx = kotlin.math.abs(sa.centroidX - sb.centroidX)
        val dy = kotlin.math.abs(sa.centroidY - sb.centroidY)
        assertTrue("centroid X must move ≥20% width (dx=$dx)", dx >= 0.20 * a.width)
        assertTrue("centroid Y must move ≥20% height (dy=$dy)", dy >= 0.20 * a.height)
    }

    @Test
    fun exportOne_textClamp_alpha0EqualsBackground_alpha255Visible() = runBlocking {
        val bg = paintBg(320, 240)
        val src = writePng("c41-alpha-bg.png", bg)
        val base = WaterMark.default.copy(
            text = "Alpha",
            textSize = 36f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            hGap = 0,
            vGap = 0,
        )
        val a0 = exportPng(src, base.copy(alpha = 0), 0.5f, 0.5f)
        val a255 = exportPng(src, base.copy(alpha = 255), 0.5f, 0.5f)
        assertTrue("alpha0 must match decoded background", bitmapsNearlyEqual(bg, a0))
        val s255 = deltaVsBg(bg, a255)
        assertTrue("alpha255 must be visible", s255.changedCount > 0)
    }

    @Test
    fun exportOne_asymmetricIcon_repeatAndClamp_preserveBreadthOffsetAndAlpha() = runBlocking {
        val bg = paintBg(320, 240)
        val bgFile = writePng("c41-icon-bg.png", bg)
        val iconFile = writePng("c41-icon.png", asymmetricIcon())
        val base = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(Uri.fromFile(iconFile).toString()),
            textSize = 14f,
            hGap = 0,
            vGap = 0,
            degree = 0f,
        )
        val repeat = exportPng(
            bgFile,
            base.copy(tileMode = WatermarkTileMode.REPEAT, alpha = 255),
            0.5f,
            0.5f,
        )
        val sRep = deltaVsBg(bg, repeat)
        assertTrue("icon REPEAT must be broad", sRep.changedCount > 100)
        assertTrue("icon REPEAT bboxW", sRep.bboxW >= (0.55 * repeat.width).toInt())
        assertTrue("icon REPEAT bboxH", sRep.bboxH >= (0.55 * repeat.height).toInt())

        val clamp255 = exportPng(
            bgFile,
            base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 255),
            0.17f,
            0.83f,
        )
        val clamp128 = exportPng(
            bgFile,
            base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 128),
            0.17f,
            0.83f,
        )
        val s255 = deltaVsBg(bg, clamp255)
        assertTrue("icon CLAMP must paint", s255.changedCount > 0)
        assertTrue("CLAMP localized W", s255.bboxW < 0.60 * clamp255.width)
        assertTrue("CLAMP localized H", s255.bboxH < 0.60 * clamp255.height)
        assertTrue("CLAMP offset left", s255.centroidX < 0.45 * clamp255.width)
        assertTrue("CLAMP offset lower", s255.centroidY > 0.55 * clamp255.height)
        // Asymmetric icon must survive Android decode: red TL, blue base, white BR markers present.
        assertAsymmetricIconMarkersPresent(clamp255, bg)

        val s128 = deltaVsBg(bg, clamp128)
        assertTrue("alpha128 CLAMP must remain visible", s128.changedCount > 0)
        assertTrue("alpha128 CLAMP localized W", s128.bboxW < 0.60 * clamp128.width)
        assertTrue("alpha128 CLAMP localized H", s128.bboxH < 0.60 * clamp128.height)
        // Same CLAMP location as alpha255 (tight centroid agreement).
        val cdx = kotlin.math.abs(s128.centroidX - s255.centroidX)
        val cdy = kotlin.math.abs(s128.centroidY - s255.centroidY)
        assertTrue(
            "alpha128 centroid must match alpha255 location (dx=$cdx dy=$cdy)",
            cdx < 0.12 * clamp255.width && cdy < 0.12 * clamp255.height,
        )

        // Alpha 128 weaker than 255 on the alpha-255 changed-pixel mask (and not zero).
        var sum255 = 0.0
        var sum128 = 0.0
        var n = 0
        for ((x, y) in s255.coords) {
            val c255 = clamp255.getPixel(x, y)
            val c128 = clamp128.getPixel(x, y)
            val b = bg.getPixel(x, y)
            sum255 += colorDist(c255, b)
            sum128 += colorDist(c128, b)
            n++
        }
        assertTrue(n > 0)
        val mean128 = sum128 / n
        val mean255 = sum255 / n
        assertTrue("alpha128 mask mean must be nonzero (visible)", mean128 > 1.0)
        assertTrue(
            "alpha128 must be weaker than alpha255 on shared mask",
            mean128 < mean255 * 0.95,
        )
    }

    @Test
    fun exportOne_jpegQuality_q20NotLargerThanQ95_sameDimensions() = runBlocking {
        val entropy = highEntropy(256, 192)
        val src = writePng("c41-entropy.png", entropy)
        val config = WaterMark.default.copy(
            text = "Q",
            textSize = 20f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.REPEAT,
            alpha = 255,
        )
        val q20 = exportJpeg(src, config, 20)
        val q95 = exportJpeg(src, config, 95)
        val b20 = decodeUri(q20)
        val b95 = decodeUri(q95)
        assertEquals(b95.width, b20.width)
        assertEquals(b95.height, b20.height)
        val bytes20 = app.contentResolver.openInputStream(q20)!!.use { it.readBytes() }
        val bytes95 = app.contentResolver.openInputStream(q95)!!.use { it.readBytes() }
        assertTrue(
            "q20 must not be larger than q95 (q20=${bytes20.size} q95=${bytes95.size})",
            bytes20.size <= bytes95.size,
        )
    }

    @Test
    fun exportOne_transparentSourceToJpeg_flattensOpaqueWhite() = runBlocking {
        val transparent = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        val src = writePng("c41-transparent.png", transparent)
        val config = WaterMark.default.copy(
            text = "T",
            textSize = 12f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            alpha = 0,
        )
        val outUri = exportJpeg(src, config, 90)
        val out = decodeUri(outUri)
        val c = out.getPixel(out.width / 2, out.height / 2)
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        assertTrue(
            "transparent→JPEG must flatten near-white, not black: r=$r g=$g b=$b",
            r > 200 && g > 200 && b > 200,
        )
        assertEquals("decoded JPEG pixel must be opaque", 255, Color.alpha(c))
    }

    @Test
    fun exportOne_large2048x1536_completesAtOriginalDimensions() = runBlocking {
        val bg = paintBg(2048, 1536)
        val src = writePng("c41-large.png", bg)
        val config = WaterMark.default.copy(
            text = "L",
            textSize = 48f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            alpha = 0,
        )
        val info = ImageInfo(
            uri = MediaRef(Uri.fromFile(src).toString()),
            width = 1,
            height = 1,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        val result = AndroidExportPipelinePort(appContext = app).exportOne(
            imageInfo = info,
            config = config,
            prefs = UserPreferences(ImageFormat.PNG, 100),
        )
        val media = requireSuccessMedia(result)
        assertEquals(2048, info.width)
        assertEquals(1536, info.height)
        assertEquals(2048, media.width)
        assertEquals(1536, media.height)
        val out = decodeUri(Uri.parse(media.ref.value))
        assertEquals(2048, out.width)
        assertEquals(1536, out.height)
    }

    private fun solidBg(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private fun paintBg(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(0x20, 0x30, 0x40))
        }

    private fun asymmetricIcon(): Bitmap {
        val bmp = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        for (y in 0 until 16) {
            for (x in 0 until 24) {
                bmp.setPixel(x, y, Color.RED)
            }
        }
        for (y in 16 until 32) {
            for (x in 24 until 48) {
                bmp.setPixel(x, y, Color.WHITE)
            }
        }
        return bmp
    }

    private fun highEntropy(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var s = 0xC0FFEEL
        for (y in 0 until h) {
            for (x in 0 until w) {
                s = (s * 1103515245L + 12345L) and 0x7fffffffL
                val v = (s % 256).toInt()
                bmp.setPixel(x, y, Color.rgb(v, (v * 3) % 256, (v * 7) % 256))
            }
        }
        return bmp
    }

    private fun writePng(name: String, bmp: Bitmap): File =
        File(app.cacheDir, name).apply {
            parentFile?.mkdirs()
            outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }

    private fun exportPng(
        src: File,
        config: WaterMark,
        ox: Float,
        oy: Float,
    ): Bitmap {
        val info = ImageInfo(
            uri = MediaRef(Uri.fromFile(src).toString()),
            width = 1,
            height = 1,
            offsetX = ox,
            offsetY = oy,
        )
        val result = runBlocking {
            AndroidExportPipelinePort(appContext = app).exportOne(
                imageInfo = info,
                config = config,
                prefs = UserPreferences(ImageFormat.PNG, 100),
            )
        }
        return decodeUri(Uri.parse(requireSuccessMedia(result).ref.value))
    }

    private fun exportJpeg(src: File, config: WaterMark, quality: Int): Uri {
        val info = ImageInfo(
            uri = MediaRef(Uri.fromFile(src).toString()),
            width = 1,
            height = 1,
            offsetX = 0.5f,
            offsetY = 0.5f,
        )
        val result = runBlocking {
            AndroidExportPipelinePort(appContext = app).exportOne(
                imageInfo = info,
                config = config,
                prefs = UserPreferences(ImageFormat.JPEG, quality),
            )
        }
        return Uri.parse(requireSuccessMedia(result).ref.value)
    }

    private fun requireSuccessMedia(result: ExportOutcome): me.rosuh.easywatermark.data.model.ExportedMedia {
        assertTrue(
            "exportOne must succeed: " +
                "${(result as? ExportOutcome.Failure)?.failure?.legacyCode} " +
                "${(result as? ExportOutcome.Failure)?.failure?.message}",
            result.isSuccess(),
        )
        return (result as ExportOutcome.Success).media
    }

    private fun decodeUri(uri: Uri): Bitmap {
        val bmp = app.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input)
        }
        assertNotNull(bmp)
        return bmp!!
    }

    private data class DeltaStats(
        val changedCount: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val centroidX: Double,
        val centroidY: Double,
        val coords: List<Pair<Int, Int>>,
    ) {
        val bboxW: Int get() = maxX - minX + 1
        val bboxH: Int get() = maxY - minY + 1
    }

    private fun deltaVsBg(bg: Bitmap, out: Bitmap): DeltaStats {
        assertEquals(bg.width, out.width)
        assertEquals(bg.height, out.height)
        var minX = out.width
        var maxX = -1
        var minY = out.height
        var maxY = -1
        var sumX = 0L
        var sumY = 0L
        val coords = ArrayList<Pair<Int, Int>>()
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDist(out.getPixel(x, y), bg.getPixel(x, y)) > 8.0) {
                    coords.add(x to y)
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                    sumX += x
                    sumY += y
                }
            }
        }
        val n = coords.size
        return DeltaStats(
            changedCount = n,
            minX = if (n == 0) 0 else minX,
            maxX = if (n == 0) 0 else maxX,
            minY = if (n == 0) 0 else minY,
            maxY = if (n == 0) 0 else maxY,
            centroidX = if (n == 0) 0.0 else sumX.toDouble() / n,
            centroidY = if (n == 0) 0.0 else sumY.toDouble() / n,
            coords = coords,
        )
    }

    private fun colorDist(a: Int, b: Int): Double {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        val da = Color.alpha(a) - Color.alpha(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db + da * da).toDouble())
    }

    private fun bitmapsNearlyEqual(a: Bitmap, b: Bitmap, eps: Int = 2): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        // Full-image compare (320×240 fixtures) — no row/column sampling.
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (colorDist(a.getPixel(x, y), b.getPixel(x, y)) > eps) return false
            }
        }
        return true
    }

    /**
     * Mutation-resistant: after blend onto #203040, the asymmetric icon must still show
     * red (top-left), blue (body), and white (bottom-right) color classes in the decal region.
     */
    private fun assertAsymmetricIconMarkersPresent(out: Bitmap, bg: Bitmap) {
        var redHits = 0
        var blueHits = 0
        var whiteHits = 0
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDist(out.getPixel(x, y), bg.getPixel(x, y)) <= 8.0) continue
                val p = out.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // Tolerant classes after alpha blend over dark teal bg.
                if (r > 140 && r > g + 30 && r > b + 30) redHits++
                if (b > 140 && b > r + 20 && b > g + 20) blueHits++
                if (r > 160 && g > 160 && b > 160) whiteHits++
            }
        }
        assertTrue("asymmetric icon red marker missing after decode/blend (hits=$redHits)", redHits > 0)
        assertTrue("asymmetric icon blue body missing after decode/blend (hits=$blueHits)", blueHits > 0)
        assertTrue("asymmetric icon white marker missing after decode/blend (hits=$whiteHits)", whiteHits > 0)
    }

    private fun queryMimeAndDisplayName(uri: Uri): Pair<String?, String?> {
        val projection = arrayOf(
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        app.contentResolver.query(uri, projection, null, null, null).use { cursor ->
            assertNotNull("MediaStore row must be queryable for $uri", cursor)
            assertTrue("MediaStore row must have one entry", cursor!!.moveToFirst())
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
            return mime to name
        }
    }

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
