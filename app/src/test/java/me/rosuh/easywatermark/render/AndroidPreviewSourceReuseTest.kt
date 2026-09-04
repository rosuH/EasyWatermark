package me.rosuh.easywatermark.render

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.ui.DraftRenderConflator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidPreviewSourceReuseTest {

    @Before
    fun enableProbe() {
        PreviewSourceReuseProbe.enabled = true
        PreviewSourceReuseProbe.reset()
    }

    @After
    fun silenceProbe() {
        PreviewSourceReuseProbe.enabled = false
        PreviewSourceReuseProbe.reset()
    }

    @Test
    fun textSizeChange_doesNotIncreaseSourceDecodes() = runBlocking {
        val repo = PreviewImageRepository<Bitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            approxBytes = { it.allocationByteCount.toLong() },
        )
        val path = "content://preview/source-reuse"
        val bucket = 720
        val srcKey = PreviewKey(path, bucket, PreviewPurpose.SourcePlaceholder)
        val wmKey = PreviewKey(path, bucket, PreviewPurpose.Watermarked)
        val source = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        repo.load(srcKey) {
            PreviewSourceReuseProbe.recordSourceDecode()
            PreviewSourceReuseProbe.recordContentResolverOpen()
            source
        }
        repo.load(wmKey) { source }
        repo.clearPurpose(PreviewPurpose.Watermarked)
        val afterClearOpens = PreviewSourceReuseProbe.snapshot().contentResolverOpens
        val afterClearDecodes = PreviewSourceReuseProbe.snapshot().sourceDecodes

        val reused = repo.load(srcKey) {
            error("textSize change must not decode Source again")
        }
        assertNotNull(reused)
        val snap = PreviewSourceReuseProbe.snapshot()
        assertEquals(afterClearDecodes, snap.sourceDecodes)
        assertEquals(afterClearOpens, snap.contentResolverOpens)
        assertEquals(1, snap.sourceDecodes)
        val json = """{"platform":"android-robolectric","sourceDecodes":${snap.sourceDecodes},"contentResolverOpens":${snap.contentResolverOpens},"composes":${snap.composes}}"""
        java.io.File("build/preview-source-reuse-bench").apply { mkdirs() }
        java.io.File("build/preview-source-reuse-bench/android-reuse-bench.json").writeText(json)
        println("PREVIEW_REUSE_BENCH $json")
        repo.close()
    }

    @Test
    fun conflator_boundsInFlightComposeToOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val conflator = DraftRenderConflator<Int>(scope) { _ ->
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            if (!firstStarted.isCompleted) {
                firstStarted.complete(Unit)
                release.await()
            }
            inFlight.decrementAndGet()
        }
        try {
            conflator.submit(0)
            firstStarted.await()
            repeat(40) { conflator.submit(it + 1) }
            release.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) {
                while (conflator.countsForTests().rendered < 2) {
                    kotlinx.coroutines.yield()
                }
            }
            assertTrue("in-flight compose must be ≤1, peak=${peak.get()}", peak.get() <= 1)
        } finally {
            conflator.close()
        }
    }

    @Test
    fun trimMemory_keepsFocusSource_dropsWatermarked() = runBlocking {
        val repo = PreviewImageRepository<Bitmap>(
            ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            approxBytes = { it.allocationByteCount.toLong() },
        )
        val focus = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val neighbor = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val wm = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        repo.putForTests(PreviewKey("focus", 720, PreviewPurpose.SourcePlaceholder), focus)
        repo.putForTests(PreviewKey("neighbor", 720, PreviewPurpose.SourcePlaceholder), neighbor)
        repo.putForTests(PreviewKey("focus", 720, PreviewPurpose.Watermarked), wm)
        AndroidPreviewWorkingSet.attach(repo)
        AndroidPreviewWorkingSet.focusPath = "focus"
        AndroidPreviewWorkingSet.onTrimMemory()
        kotlinx.coroutines.delay(50)
        assertNotNull(repo.cached(PreviewKey("focus", 720, PreviewPurpose.SourcePlaceholder)))
        assertNull(repo.cached(PreviewKey("neighbor", 720, PreviewPurpose.SourcePlaceholder)))
        assertNull(repo.cached(PreviewKey("focus", 720, PreviewPurpose.Watermarked)))
        AndroidPreviewWorkingSet.detach(repo)
        repo.close()
    }

    @Test
    fun waterMarkCanvas_paintPath_isLiveOverlayNotBakedFrame() {
        val cwd = java.io.File(System.getProperty("user.dir")!!)
        val relative = "src/main/java/me/rosuh/easywatermark/ui/AndroidEditorScreen.kt"
        val sourceFile = linkedSetOf(
            java.io.File(cwd, relative),
            java.io.File(cwd, "app/$relative"),
            java.io.File(cwd.parentFile ?: cwd, "app/$relative"),
        ).firstOrNull { it.isFile } ?: error("AndroidEditorScreen.kt not found from $cwd")
        val body = sourceFile.readText()
        val canvasStart = body.indexOf("private fun WaterMarkCanvas(")
        assertTrue("WaterMarkCanvas must exist", canvasStart >= 0)
        val canvasEnd = body.indexOf("private data class ContentRect", canvasStart)
        val canvas = body.substring(
            canvasStart,
            if (canvasEnd > canvasStart) canvasEnd else body.length,
        )
        assertTrue("WaterMarkCanvas must gate LiveLayers", "OverlayPreviewPolicy" in canvas)
        assertTrue("WaterMarkCanvas must paint LiveOverlayPreview", "LiveOverlayPreview" in canvas)
        assertTrue("WaterMarkCanvas must compose a cell", "composeCell" in canvas)
        assertTrue(
            "must not publish Source via showSourceWhileComposing",
            "showSourceWhileComposing" !in canvas,
        )
        assertTrue(
            "editor paint must not bake via composeToBitmap",
            "composeToBitmap" !in canvas,
        )
        val prefetchStart = body.indexOf("private suspend fun prefetchAndroidNeighbors")
        assertTrue(prefetchStart >= 0)
        val prefetch = body.substring(prefetchStart)
        assertTrue("neighbor prefetch must not bake", "composeToBitmap" !in prefetch)
        assertTrue("neighbor prefetch must warm Source", "SourcePlaceholder" in prefetch)
        val effectStart = canvas.indexOf("LaunchedEffect(selectedUri, cw, ch, wmFp, bucket)")
        assertTrue("config/path paint must be keyed on wmFp", effectStart >= 0)
        val effect = canvas.substring(effectStart, (effectStart + 900).coerceAtMost(canvas.length))
        assertTrue(
            "same-path style ticks must keep last LiveLayers; dropping photo paints WaitThumb",
            "livePhoto = null" !in effect && "overlay = null" !in effect,
        )
    }

    @Test
    fun waterMarkCanvas_clampCommit_ownsNonDraftPaintAndDropsStaleToken() {
        val cwd = java.io.File(System.getProperty("user.dir")!!)
        val relative = "src/main/java/me/rosuh/easywatermark/ui/AndroidEditorScreen.kt"
        val sourceFile = linkedSetOf(
            java.io.File(cwd, relative),
            java.io.File(cwd, "app/$relative"),
            java.io.File(cwd.parentFile ?: cwd, "app/$relative"),
        ).firstOrNull { it.isFile } ?: error("AndroidEditorScreen.kt not found from $cwd")
        val canvasStart = sourceFile.readText().indexOf("private fun WaterMarkCanvas(")
        assertTrue("WaterMarkCanvas must exist", canvasStart >= 0)
        val body = sourceFile.readText()
        val canvasEnd = body.indexOf("private data class ContentRect", canvasStart)
        val canvas = body.substring(
            canvasStart,
            if (canvasEnd > canvasStart) canvasEnd else body.length,
        )
        assertTrue(
            "stale-token drop must not be draft-only",
            !Regex("""req\.token\s*!=\s*paintToken\s*&&\s*req\.isDraft""").containsMatchIn(canvas),
        )
        assertTrue(
            "CLAMP commit must own a non-draft paint helper",
            "fun submitCommittedOverlayPaint" in canvas,
        )
        assertTrue(
            "commit paint must bump paintToken",
            "paintToken += 1" in canvas,
        )
        val persist = canvas.substring(canvas.indexOf("onDragEnd"))
        assertTrue(
            "rubber-band persist must enqueue committed paint",
            persist.contains("submitCommittedOverlayPaint(centerX, centerY)"),
        )
        assertTrue(
            "in-bounds persist must enqueue committed paint",
            persist.contains("submitCommittedOverlayPaint(offsetX, offsetY)"),
        )
        assertTrue(
            "commit must not drop live layers",
            "livePhoto = null" !in persist.substring(0, 2500.coerceAtMost(persist.length)),
        )
    }
}
