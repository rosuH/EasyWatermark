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
}
