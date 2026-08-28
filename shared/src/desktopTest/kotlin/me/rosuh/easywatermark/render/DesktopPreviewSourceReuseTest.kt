package me.rosuh.easywatermark.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.WaterMark
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopPreviewSourceReuseTest {

    @BeforeTest
    fun enableProbe() {
        PreviewSourceReuseProbe.enabled = true
        PreviewSourceReuseProbe.reset()
    }

    @AfterTest
    fun silenceProbe() {
        PreviewSourceReuseProbe.enabled = false
        PreviewSourceReuseProbe.reset()
    }

    @Test
    fun desktopWindow_paintPath_hasNoDebounceOrDataStoreFirst() {
        val window = desktopWindowSource()
        val previewStart = window.indexOf("suspend fun refreshPreviewLight")
        assertTrue(previewStart >= 0)
        val previewEnd = window.indexOf("data class DesktopPreviewPaint", previewStart).let {
            if (it > previewStart) it else window.length
        }
        val body = window.substring(previewStart, previewEnd)
        assertTrue("waterMark.first()" !in body, "paint path must use Session snapshot")
        assertTrue("delay(250)" !in window, "250ms debounce must be gone")
        assertTrue("DraftRenderConflator" in window)
        assertTrue("PreviewImageRepository" in window)
        assertTrue(
            "OverlayPreviewPolicy" in body || "canPublishLivePhoto" in body,
            "paint path must gate LiveLayers through OverlayPreviewPolicy",
        )
        assertTrue(
            "showSourceWhileComposing" !in body,
            "must not publish Source onto the editor preview via showSourceWhileComposing",
        )
        assertTrue(
            "renderWatermarked" !in body,
            "editor paint must not bake via renderWatermarked",
        )
        assertTrue(
            "composeDesktopOverlayCell" in window || "composeCell" in body,
            "editor paint must compose an overlay cell, not a baked frame",
        )
    }

    @Test
    fun filmstripSwitch_awaitsFocusPaint_beforeNeighborPrefetch() {
        val window = desktopWindowSource()
        val selectStart = window.indexOf("onImageSelected = { info ->")
        assertTrue(selectStart >= 0, "must define onImageSelected")
        val selectEnd = window.indexOf("onConfigChange", selectStart)
        val select = window.substring(selectStart, if (selectEnd > selectStart) selectEnd else window.length)
        val paintIdx = select.indexOf("refreshPreviewLight")
        val lastPrefetchIdx = select.lastIndexOf("prefetchNeighborWatermarked")
        assertTrue(paintIdx >= 0, "switch must await focus refreshPreviewLight")
        assertTrue(lastPrefetchIdx >= 0, "switch still warms focus±2 after the focus paint")
        assertTrue(
            paintIdx < lastPrefetchIdx,
            "cache-miss neighbor prefetch must start only after focus paint returns",
        )
        assertTrue(
            !Regex("""submitPreviewPaint\s*\(""").containsMatchIn(select),
            "filmstrip switch must not queue behind the slider conflator",
        )
        assertTrue(
            "clearLiveLayers" in select,
            "switch must drop the previous live preview immediately",
        )
        assertTrue(
            "preview = " !in select && "previewPhoto =" !in select,
            "switch must not optimistic-paint Source or a baked Watermarked frame",
        )
        val recomputeStart = window.indexOf("fun recomputeCommittedPreviewBucket")
        val recomputeEnd = window.indexOf("fun onPreviewBoxSizeChanged", recomputeStart)
        val recompute = window.substring(
            recomputeStart,
            if (recomputeEnd > recomputeStart) recomputeEnd else window.length,
        )
        assertTrue(
            "submitPreviewPaint" !in recompute,
            "bucket recompute must not enqueue a second paint that races the switch",
        )
        assertTrue(
            "committedMaxEdgePxForFit" !in recompute,
            "per-image Fit buckets flip 2560↔1440 on aspect change and miss every click",
        )
        assertTrue(
            "committedMaxEdgePx(" in recompute,
            "Desktop switch must keep one pane-stable committed bucket",
        )
    }

    @Test
    fun neighborPrefetch_decodeAndCompose_mustHopOffMain() {
        val window = desktopWindowSource()
        val start = window.indexOf("fun prefetchNeighborWatermarked")
        assertTrue(start >= 0, "missing prefetchNeighborWatermarked")
        val end = window.indexOf("fun recomputeCommittedPreviewBucket", start)
        val body = window.substring(start, if (end > start) end else window.length)
        assertTrue(
            body.contains("withContext(Dispatchers.IO)") &&
                body.contains("decodeSourcePlaceholder"),
            "repository completion is Main; ImageIO in prefetch must hop IO",
        )
        assertTrue(
            "renderWatermarked" !in body,
            "neighbor prefetch must not bake Watermarked frames",
        )
        val refreshStart = window.indexOf("suspend fun refreshPreviewLight")
        val refreshEnd = window.indexOf("data class DesktopPreviewPaint", refreshStart)
        val refresh = window.substring(
            refreshStart,
            if (refreshEnd > refreshStart) refreshEnd else window.length,
        )
        assertTrue("previewImages.load(srcKey)" in refresh)
        assertTrue(
            "showSourceWhileComposing" !in refresh,
            "cache-miss must not paint Source before the overlay is ready",
        )
        assertTrue(
            "renderWatermarked" !in refresh,
            "refreshPreviewLight must not bake the editor preview",
        )
        assertTrue(
            "canPublishLivePhoto" in refresh && "composeDesktopOverlayCell" in refresh,
            "Source + cell must publish atomically through OverlayPreviewPolicy",
        )
        val persistStart = window.indexOf("persistHandler.value = { change ->")
        assertTrue(persistStart >= 0, "missing persistHandler config path")
        val persistEnd = window.indexOf("fun submitPreviewPaint", persistStart)
        val persist = window.substring(
            persistStart,
            if (persistEnd > persistStart) persistEnd else persistStart + 800,
        )
        assertTrue(
            "previewPhoto = null" !in persist && "overlayCell = null" !in persist,
            "same-path style ticks must keep last LiveLayers; dropping photo paints WaitThumb",
        )
    }

    @Test
    fun productAsyncImage_retriesCoilError() {
        val cwd = java.io.File(System.getProperty("user.dir")!!)
        val src = listOf(
            java.io.File(cwd, "src/commonMain/kotlin/me/rosuh/easywatermark/ui/image/ProductAsyncImage.kt"),
            java.io.File(cwd, "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/image/ProductAsyncImage.kt"),
        ).first { it.isFile }.readText()
        assertTrue("shouldRetry" in src)
        assertTrue("retryTick" in src)
        assertTrue("warmProductThumbs" !in desktopWindowSource())
    }

    private fun desktopWindowSource(): String {
        val cwd = java.io.File(System.getProperty("user.dir")!!)
        return listOf(
            java.io.File(cwd, "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt"),
            java.io.File(cwd.parentFile, "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt"),
        ).first { it.isFile }.readText()
    }

    @Test
    fun secondConfigPaint_samePathAndBucket_decodesSourceOnce() = runBlocking {
        val dir = File("build/preview-source-reuse-desktop").apply { mkdirs() }
        val file = File(dir, "src.png")
        writePng(file, 800, 600)
        val bytes = file.readBytes()
        val repo = PreviewImageRepository<ImageBitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            approxBytes = { PreviewImageRepository.approxImageBitmapBytes(it) },
        )
        val bucket = 720
        val srcKey = PreviewKey(file.absolutePath, bucket, PreviewPurpose.SourcePlaceholder)
        val wmKey = PreviewKey(file.absolutePath, bucket, PreviewPurpose.Watermarked)
        val wm = WaterMark.default

        val source = repo.load(srcKey) {
            DesktopPreviewRaster.decodeSourcePlaceholder(bytes, bucket)
        }
        assertNotNull(source)
        val first = DesktopPreviewRaster.renderWatermarked(
            imageBytes = ByteArray(0),
            waterMark = wm,
            offsetX = 0.5f,
            offsetY = 0.5f,
            maxEdgePx = bucket,
            background = source,
        )
        repo.load(wmKey) { first }
        repo.clearPurpose(PreviewPurpose.Watermarked)

        val reused = repo.load(srcKey) { error("second paint must not decode Source") }
        assertNotNull(reused)
        DesktopPreviewRaster.renderWatermarked(
            imageBytes = ByteArray(0),
            waterMark = wm.copy(textSize = wm.textSize + 4f),
            offsetX = 0.5f,
            offsetY = 0.5f,
            maxEdgePx = bucket,
            background = reused,
        )

        val snap = PreviewSourceReuseProbe.snapshot()
        assertEquals(1, snap.sourceDecodes, "same path+bucket must decode Source once, got $snap")
        assertTrue(snap.composes >= 2, "both paints must compose, got $snap")
        repo.close()
    }

    @Test
    fun neighborPrefetch_n5Focus2_canPeekPlusMinusTwo() = runBlocking {
        val dir = File("build/preview-source-reuse-desktop").apply { mkdirs() }
        val repo = PreviewImageRepository<ImageBitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            approxBytes = { PreviewImageRepository.approxImageBitmapBytes(it) },
        )
        val bucket = 320
        val paths = (0 until 5).map { i ->
            File(dir, "n$i.png").also { writePng(it, 160, 120) }.absolutePath
        }
        val focus = 2
        val frame = ImageBitmap(160, 120, ImageBitmapConfig.Argb8888)
        repo.putForTests(PreviewKey(paths[focus], bucket, PreviewPurpose.Watermarked), frame)
        for (i in neighborIndices(focus, paths.size)) {
            repo.putForTests(PreviewKey(paths[i], bucket, PreviewPurpose.Watermarked), frame)
        }
        for (i in neighborIndices(focus, paths.size)) {
            assertNotNull(
                repo.peekCached(PreviewKey(paths[i], bucket, PreviewPurpose.Watermarked)),
                "neighbor $i must be peekable after focus±2 warm",
            )
        }
        repo.close()
    }

    private fun writePng(file: File, width: Int, height: Int) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(32, 64, 96)
        g.fillRect(0, 0, width, height)
        g.dispose()
        ByteArrayOutputStream().use { out ->
            ImageIO.write(img, "png", out)
            file.writeBytes(out.toByteArray())
        }
    }
}
