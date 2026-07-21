@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
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
import me.rosuh.easywatermark.data.repo.IosIconPersistence
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosFinalRenderSpine
import me.rosuh.easywatermark.render.IosImageDecoder
import me.rosuh.easywatermark.render.IosRenderRequest
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sole adapter owner for [IosExportPipelinePort] (C3 / C4.3):
 * validation, result mapping, and platform paint/output **forwarding** sentinels.
 *
 * Encode quality / white-flatten / sRGB / orientation remain in [IosFinalRenderSpineTest].
 * Exact glyph/tofu/multiline semantics remain in Common tests.
 *
 * Text-mode tests inject a recording [FontFamily.Monospace] via the internal construction seam
 * (K/N test kexe has no app-bundled Noto). Image-mode tests inject a provider that fails if called.
 */
class IosExportPipelinePortTest {

    private val bgColor = Color(0xFF203040)
    /** ~8/255 on 0–1 channels — decoded-pixel threshold. */
    private val rgbEps = 0.032f
    private val pngMagic = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    // ─── Existing three Port contracts (retained) ───────────────────────────

    @Test
    fun exportOne_missingSource_fails() = runBlocking {
        val port = IosExportPipelinePort(imageModeFontProvider())
        val result = port.exportOne(
            ImageInfo(MediaRef("/tmp/ewm_does_not_exist_phase4.png")),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(result.isFailure())
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, result.code)
    }

    /**
     * Sole full-resolution JPEG success E2E: 2048×1536; JPEG prefs → `.jpg` + magic; Port once.
     * Image mode: font provider must not be invoked.
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

        try {
            val port = IosExportPipelinePort(imageModeFontProvider())
            val result = port.exportOne(imageInfo, config, prefs)
            assertTrue(result.isSuccess(), "code=${result.code} msg=${result.message}")
            val outputPath = result.data!!.value
            assertTrue(outputPath.endsWith(".jpg"), "JPEG prefs must yield .jpg path")
            val outputData = NSData.dataWithContentsOfFile(outputPath)
            assertNotNull(outputData)
            val outputBytes = IosByteArrayInterop.fromNSData(outputData)
            assertTrue(
                outputBytes[0] == 0xFF.toByte() &&
                    outputBytes[1] == 0xD8.toByte() &&
                    outputBytes[2] == 0xFF.toByte(),
                "JPEG magic required",
            )
            assertEquals(2048, imageInfo.width)
            assertEquals(1536, imageInfo.height)
            assertEquals(2048, spine.width)
            assertEquals(1536, spine.height)
            assertContentEquals(spine.bytes, outputBytes)
            deletePath(outputPath)
        } finally {
            deletePath(sourcePath)
            deletePath(iconPath)
        }
    }

    /**
     * Issue 22 §2.5: on atomic-write failure, [ImageInfo] width/height must remain unchanged.
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
        val port = IosExportPipelinePort(imageModeFontProvider())
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
            deletePath(sourcePath)
            deletePath(iconPath)
        }
    }

    // ─── C4.3 Port paint/output forwarding sentinels (issue 24 §6) ──────────

    /**
     * PNG result/mapping + CJK multiline REPEAT partial-alpha breadth, α128 weaker than α255
     * on the true α128∩α255 mask, and direct-Spine byte parity proving the injected family is forwarded.
     */
    @Test
    fun exportOne_pngCjkMultilineRepeat_partialAlpha_isBroadAndWeakerThan255() = runBlocking {
        val w = 320
        val h = 240
        val bgBytes = IosWatermarkRenderer.encodePng(solidBitmap(w, h, bgColor))
        val sourcePath = uniqueTemp("c43_cjk_src", ".png")
        assertTrue(IosByteArrayInterop.toNSData(bgBytes).writeToFile(sourcePath, atomically = true))

        val base = WaterMark.default.copy(
            text = "请勿转载\nDO NOT COPY",
            textSize = 28f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.REPEAT,
            degree = 315f,
            hGap = 0,
            vGap = 0,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val recorder = RecordingFontProvider()
        val port = IosExportPipelinePort(recorder)
        val outPaths = mutableListOf<String>()
        try {
            val info128 = ImageInfo(MediaRef(sourcePath), width = 1, height = 1, offsetX = 0.5f, offsetY = 0.5f)
            assertEquals(1, info128.width)
            assertEquals(1, info128.height)

            val r128 = port.exportOne(info128, base.copy(alpha = 128), prefs)
            assertTrue(r128.isSuccess(), "α128 PNG: ${r128.code} ${r128.message}")
            val p128 = r128.data!!.value
            outPaths += p128
            assertTrue(p128.endsWith(".png"), "PNG prefs → .png")
            val b128 = readFile(p128)
            assertPngMagic(b128)
            assertEquals(w, info128.width, "ImageInfo mutates only after PNG success")
            assertEquals(h, info128.height)
            val d128 = IosImageDecoder.decode(b128)
            assertEquals(w, d128.width)
            assertEquals(h, d128.height)

            // Direct-Spine parity with the same injected family proves forwarding, not mere invocation.
            val alpha128Request = IosRenderRequest(base.copy(alpha = 128), prefs, 0.5f, 0.5f)
            val spine128Mono = IosFinalRenderSpine.renderAndEncode(
                bgBytes,
                alpha128Request,
                fontFamily = FontFamily.Monospace,
            )
            assertContentEquals(
                spine128Mono.bytes,
                b128,
                "Port Text PNG must match direct Spine with the same injected FontFamily",
            )
            // Mutation control: if both Port and Spine ignored the family, they would also match
            // default-resolver (null) output. Require Monospace/Port bytes to differ from null-family.
            val spine128Null = IosFinalRenderSpine.renderAndEncode(
                bgBytes,
                alpha128Request,
                fontFamily = null,
            )
            assertTrue(
                !spine128Null.bytes.contentEquals(b128),
                "injected Monospace/Port α128 bytes must differ from direct Spine(fontFamily=null)",
            )
            assertTrue(
                !spine128Null.bytes.contentEquals(spine128Mono.bytes),
                "direct Spine(Monospace) must differ from direct Spine(null) for the same request",
            )

            val r255 = port.exportOne(
                ImageInfo(MediaRef(sourcePath), width = 1, height = 1, offsetX = 0.5f, offsetY = 0.5f),
                base.copy(alpha = 255),
                prefs,
            )
            assertTrue(r255.isSuccess(), "α255 PNG: ${r255.code} ${r255.message}")
            val p255 = r255.data!!.value
            outPaths += p255
            val b255 = readFile(p255)

            assertEquals(2, recorder.calls, "Text provider must run once per Text export (2 exports)")

            val s128 = deltaVsBg(bgBytes, b128)
            val s255 = deltaVsBg(bgBytes, b255)
            assertTrue(s128.changedCount > 50, "CJK REPEAT α128 must paint (n=${s128.changedCount})")
            assertTrue(s128.bboxW >= (0.55 * w).toInt(), "broad W bbox=${s128.bboxW}")
            assertTrue(s128.bboxH >= (0.55 * h).toInt(), "broad H bbox=${s128.bboxH}")
            assertTrue(s255.changedCount > 50, "α255 control must paint")

            assertAlpha128WeakerOnIntersection(bgBytes, b128, b255)
        } finally {
            outPaths.forEach { deletePath(it) }
            deletePath(sourcePath)
        }
    }

    /**
     * Text CLAMP offset pair + alpha0/255 forwarding through the real Port (geometry only).
     */
    @Test
    fun exportOne_textClamp_offsetPairAndAlpha_forwardsGeometry() = runBlocking {
        val w = 320
        val h = 240
        val bgBytes = IosWatermarkRenderer.encodePng(solidBitmap(w, h, bgColor))
        val sourcePath = uniqueTemp("c43_text_clamp_src", ".png")
        assertTrue(IosByteArrayInterop.toNSData(bgBytes).writeToFile(sourcePath, atomically = true))

        val base = WaterMark.default.copy(
            text = "TOP\nBOTTOM",
            textSize = 32f,
            markMode = WatermarkMode.Text,
            tileMode = WatermarkTileMode.CLAMP,
            degree = 0f,
            hGap = 0,
            vGap = 0,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val recorder = RecordingFontProvider()
        val port = IosExportPipelinePort(recorder)
        val outs = mutableListOf<String>()
        try {
            val a = exportPngPort(port, sourcePath, base.copy(alpha = 255), prefs, 0.17f, 0.83f)
            val b = exportPngPort(port, sourcePath, base.copy(alpha = 255), prefs, 0.83f, 0.17f)
            outs += a.path
            outs += b.path
            assertEquals(w, a.infoWidth)
            assertEquals(h, a.infoHeight)

            val sa = deltaVsBg(bgBytes, a.bytes)
            val sb = deltaVsBg(bgBytes, b.bytes)
            assertTrue(sa.changedCount > 0 && sb.changedCount > 0)
            assertTrue(sa.bboxW < 0.75 * w && sa.bboxH < 0.75 * h, "CLAMP A localized")
            assertTrue(sb.bboxW < 0.75 * w && sb.bboxH < 0.75 * h, "CLAMP B localized")
            val dx = sb.centroidX - sa.centroidX
            val dy = sa.centroidY - sb.centroidY
            assertTrue(dx >= 0.20 * w, "centroid must move right ≥20% (dx=$dx)")
            assertTrue(dy >= 0.20 * h, "centroid must move up ≥20% (dy=$dy)")

            val a0 = exportPngPort(port, sourcePath, base.copy(alpha = 0), prefs, 0.5f, 0.5f)
            val a255 = exportPngPort(port, sourcePath, base.copy(alpha = 255), prefs, 0.5f, 0.5f)
            outs += a0.path
            outs += a255.path
            assertTrue(
                bitmapsNearlyEqual(bgBytes, a0.bytes),
                "alpha0 must match decoded input background at every pixel",
            )
            assertTrue(
                deltaVsBg(bgBytes, a255.bytes).changedCount > 0,
                "alpha255 must be visible",
            )
            // 4 Text exports (offset pair + α0 + α255)
            assertEquals(4, recorder.calls, "Text provider call count must match Text exports")
        } finally {
            outs.forEach { deletePath(it) }
            deletePath(sourcePath)
        }
    }

    /**
     * Asymmetric icon via [IosIconPersistence]: REPEAT breadth, CLAMP geometry, α128 weaker, R/B/W markers.
     * Font provider must never be called (Image mode).
     */
    @Test
    fun exportOne_asymmetricIcon_repeatClampAlphaAndContent() = runBlocking {
        val w = 320
        val h = 240
        val bgBytes = IosWatermarkRenderer.encodePng(solidBitmap(w, h, bgColor))
        val sourcePath = uniqueTemp("c43_icon_src", ".png")
        assertTrue(IosByteArrayInterop.toNSData(bgBytes).writeToFile(sourcePath, atomically = true))

        val iconPath = IosIconPersistence.writeIconBytes(asymmetricIconPng())
        val base = WaterMark.default.copy(
            markMode = WatermarkMode.Image,
            iconUri = MediaRef(iconPath),
            textSize = 14f,
            hGap = 0,
            vGap = 0,
            degree = 0f,
        )
        val prefs = UserPreferences(ImageFormat.PNG, 100)
        val port = IosExportPipelinePort(imageModeFontProvider())
        val outs = mutableListOf<String>()
        try {
            val rep = exportPngPort(
                port,
                sourcePath,
                base.copy(tileMode = WatermarkTileMode.REPEAT, alpha = 255),
                prefs,
                0.5f,
                0.5f,
            )
            outs += rep.path
            val sRep = deltaVsBg(bgBytes, rep.bytes)
            assertTrue(sRep.changedCount > 100, "icon REPEAT must paint (n=${sRep.changedCount})")
            assertTrue(sRep.bboxW >= (0.55 * w).toInt(), "icon REPEAT bboxW")
            assertTrue(sRep.bboxH >= (0.55 * h).toInt(), "icon REPEAT bboxH")

            val cA = exportPngPort(
                port,
                sourcePath,
                base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 255),
                prefs,
                0.17f,
                0.83f,
            )
            val cB = exportPngPort(
                port,
                sourcePath,
                base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 255),
                prefs,
                0.83f,
                0.17f,
            )
            outs += cA.path
            outs += cB.path
            val sa = deltaVsBg(bgBytes, cA.bytes)
            val sb = deltaVsBg(bgBytes, cB.bytes)
            assertTrue(sa.changedCount > 0 && sb.changedCount > 0)
            assertTrue(sa.bboxW < 0.75 * w && sa.bboxH < 0.75 * h, "icon CLAMP A localized")
            assertTrue(sb.bboxW < 0.75 * w && sb.bboxH < 0.75 * h, "icon CLAMP B localized")
            assertTrue(sb.centroidX - sa.centroidX >= 0.20 * w, "icon centroid dx")
            assertTrue(sa.centroidY - sb.centroidY >= 0.20 * h, "icon centroid dy")
            assertAsymmetricIconMarkers(cA.bytes, bgBytes)

            val c128 = exportPngPort(
                port,
                sourcePath,
                base.copy(tileMode = WatermarkTileMode.CLAMP, alpha = 128),
                prefs,
                0.17f,
                0.83f,
            )
            outs += c128.path
            val s128 = deltaVsBg(bgBytes, c128.bytes)
            assertTrue(s128.changedCount > 0, "icon α128 must remain visible")
            assertAlpha128WeakerOnIntersection(bgBytes, c128.bytes, cA.bytes)
        } finally {
            outs.forEach { deletePath(it) }
            deletePath(sourcePath)
            IosIconPersistence.deleteIfOwned(iconPath)
        }
    }

    // ─── Helpers (Port-test only) ───────────────────────────────────────────

    /** Recording Text provider: returns [FontFamily.Monospace] and counts invocations. */
    private class RecordingFontProvider : () -> FontFamily? {
        var calls: Int = 0
            private set

        override fun invoke(): FontFamily? {
            calls += 1
            return FontFamily.Monospace
        }
    }

    /** Image-mode inject: any invocation is a hard failure (provider must not run). */
    private fun imageModeFontProvider(): () -> FontFamily? = {
        fail("Image-mode Port export must not invoke the Text font-family provider")
        null
    }

    private data class PortPngExport(
        val path: String,
        val bytes: ByteArray,
        val infoWidth: Int,
        val infoHeight: Int,
    )

    private suspend fun exportPngPort(
        port: IosExportPipelinePort,
        sourcePath: String,
        config: WaterMark,
        prefs: UserPreferences,
        ox: Float,
        oy: Float,
    ): PortPngExport {
        val info = ImageInfo(
            uri = MediaRef(sourcePath),
            width = 1,
            height = 1,
            offsetX = ox,
            offsetY = oy,
        )
        val result = port.exportOne(info, config, prefs)
        assertTrue(result.isSuccess(), "export failed: ${result.code} ${result.message}")
        val path = result.data!!.value
        assertTrue(path.endsWith(".png"), "expected .png, got $path")
        val bytes = readFile(path)
        assertPngMagic(bytes)
        return PortPngExport(path, bytes, info.width, info.height)
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
        val bboxW: Int get() = if (changedCount == 0) 0 else maxX - minX + 1
        val bboxH: Int get() = if (changedCount == 0) 0 else maxY - minY + 1
    }

    private fun colorDist(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        val da = a.alpha - b.alpha
        return sqrt(dr * dr + dg * dg + db * db + da * da)
    }

    private fun deltaVsBg(backgroundPng: ByteArray, outputBytes: ByteArray): DeltaStats {
        val bg = IosImageDecoder.decode(backgroundPng).toPixelMap()
        val out = IosImageDecoder.decode(outputBytes).toPixelMap()
        assertEquals(bg.width, out.width)
        assertEquals(bg.height, out.height)
        var minX = out.width
        var maxX = -1
        var minY = out.height
        var maxY = -1
        var sumX = 0.0
        var sumY = 0.0
        val coords = ArrayList<Pair<Int, Int>>()
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDist(out[x, y], bg[x, y]) > rgbEps) {
                    coords.add(x to y)
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
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
            centroidX = if (n == 0) 0.0 else sumX / n,
            centroidY = if (n == 0) 0.0 else sumY / n,
            coords = coords,
        )
    }

    private fun bitmapsNearlyEqual(
        backgroundPng: ByteArray,
        outputBytes: ByteArray,
        eps: Float = 0.01f,
    ): Boolean {
        val bg = IosImageDecoder.decode(backgroundPng).toPixelMap()
        val out = IosImageDecoder.decode(outputBytes).toPixelMap()
        if (bg.width != out.width || bg.height != out.height) return false
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                if (colorDist(out[x, y], bg[x, y]) > eps) return false
            }
        }
        return true
    }

    /**
     * True shared mask = coordinates where **both** α128 and α255 exceed the bg delta threshold.
     * α255-only masks are a review failure (issue 24 §6.3).
     */
    private fun assertAlpha128WeakerOnIntersection(
        backgroundPng: ByteArray,
        out128: ByteArray,
        out255: ByteArray,
    ) {
        val bg = IosImageDecoder.decode(backgroundPng).toPixelMap()
        val p128 = IosImageDecoder.decode(out128).toPixelMap()
        val p255 = IosImageDecoder.decode(out255).toPixelMap()
        assertEquals(bg.width, p128.width)
        assertEquals(bg.height, p128.height)
        assertEquals(bg.width, p255.width)
        assertEquals(bg.height, p255.height)
        var sum128 = 0.0
        var sum255 = 0.0
        var n = 0
        for (y in 0 until bg.height) {
            for (x in 0 until bg.width) {
                val d128 = colorDist(p128[x, y], bg[x, y])
                val d255 = colorDist(p255[x, y], bg[x, y])
                if (d128 > rgbEps && d255 > rgbEps) {
                    sum128 += d128.toDouble()
                    sum255 += d255.toDouble()
                    n++
                }
            }
        }
        assertTrue(n >= 100, "true α128∩α255 changed-pixel mask must have ≥100 samples (n=$n)")
        val mean128 = sum128 / n
        val mean255 = sum255 / n
        assertTrue(mean128 > 0.004, "α128 mean delta on intersection must be nonzero (visible)")
        assertTrue(
            mean128 < mean255,
            "α128 mean RGB delta must be weaker than α255 on intersection (m128=$mean128 m255=$mean255)",
        )
    }

    private fun assertAsymmetricIconMarkers(outputBytes: ByteArray, backgroundPng: ByteArray) {
        val bg = IosImageDecoder.decode(backgroundPng).toPixelMap()
        val out = IosImageDecoder.decode(outputBytes).toPixelMap()
        var redHits = 0
        var blueHits = 0
        var whiteHits = 0
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val oc = out[x, y]
                val bc = bg[x, y]
                if (colorDist(oc, bc) <= rgbEps) continue
                if (oc.red > 0.55f && oc.red > oc.green + 0.12f && oc.red > oc.blue + 0.12f) redHits++
                if (oc.blue > 0.55f && oc.blue > oc.red + 0.08f && oc.blue > oc.green + 0.08f) blueHits++
                if (oc.red > 0.63f && oc.green > 0.63f && oc.blue > 0.63f) whiteHits++
            }
        }
        assertTrue(redHits > 0, "asymmetric icon red marker missing (hits=$redHits)")
        assertTrue(blueHits > 0, "asymmetric icon blue body missing (hits=$blueHits)")
        assertTrue(whiteHits > 0, "asymmetric icon white marker missing (hits=$whiteHits)")
    }

    private fun assertPngMagic(bytes: ByteArray) {
        assertTrue(bytes.size >= 8, "PNG too short")
        for (i in pngMagic.indices) {
            assertEquals(pngMagic[i], bytes[i], "PNG magic[$i]")
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

    private fun asymmetricIconPng(): ByteArray {
        val w = 48
        val h = 32
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(color = Color.Blue)
            drawRect(color = Color.Red, topLeft = Offset.Zero, size = Size(w / 2f, h / 2f))
            drawRect(
                color = Color.White,
                topLeft = Offset(w * 0.75f, h * 0.75f),
                size = Size(w * 0.2f, h * 0.2f),
            )
        }
        return IosWatermarkRenderer.encodePng(bmp)
    }

    private fun uniqueTemp(prefix: String, suffix: String): String =
        NSTemporaryDirectory() + prefix + "_" + NSUUID().UUIDString() + suffix

    private fun readFile(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path)
        assertNotNull(data, "missing file $path")
        return IosByteArrayInterop.fromNSData(data)
    }

    private fun deletePath(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
