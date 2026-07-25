package me.rosuh.easywatermark.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ExportedMedia
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * D2 C1–C4: Session export cancellation and terminal counts on the production seam
 * ([WatermarkSessionViewModel.exportAndAwait] / [cancelExport] / [ExportPipelinePort]).
 */
class ExportCancellationSessionTest {

    /** Blocks on [gate] for each call until released; records call order. */
    private class GatedSuccessPort(
        private val gate: CompletableDeferred<Unit>? = null,
        private val throwCancelOnIndex: Int? = null,
    ) : ExportPipelinePort {
        val received = mutableListOf<MediaRef>()
        private var index = 0

        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: me.rosuh.easywatermark.data.model.WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            val i = index++
            received += imageInfo.uri
            gate?.await()
            if (throwCancelOnIndex == i) {
                throw CancellationException("port cancel mid-item")
            }
            return ExportOutcome.success(
                ExportedMedia(
                    ref = MediaRef("file:///out/${imageInfo.uri.value.hashCode()}.jpg"),
                    width = 8,
                    height = 8,
                    format = ImageFormat.JPEG,
                    byteCount = 16L,
                ),
            )
        }
    }

    /** First call succeeds after [afterFirst] completes; subsequent calls wait on [laterGate]. */
    private class FirstThenHoldPort(
        private val afterFirst: CompletableDeferred<Unit>,
        private val laterGate: CompletableDeferred<Unit>,
    ) : ExportPipelinePort {
        val received = mutableListOf<MediaRef>()
        private var index = 0

        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: me.rosuh.easywatermark.data.model.WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            val i = index++
            received += imageInfo.uri
            if (i == 0) {
                afterFirst.complete(Unit)
            } else {
                laterGate.await()
            }
            return ExportOutcome.success(
                ExportedMedia(
                    ref = MediaRef("file:///out/$i.jpg"),
                    width = 4,
                    height = 4,
                    format = ImageFormat.JPEG,
                    byteCount = 8L,
                ),
            )
        }
    }

    private fun tempDir(name: String): File =
        File("build/d2-cancel-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun newSession(dir: File, port: ExportPipelinePort): WatermarkSessionViewModel {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(File(dir, "wm-store")),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userRepo = UserConfigRepository(createUserConfigDataStore(File(dir, "user-store")))
        return WatermarkSessionViewModel(
            waterMarkRepo = waterRepo,
            userConfigRepo = userRepo,
            exportPipeline = port,
        )
    }

    private fun batch(n: Int): List<ImageInfo> =
        (0 until n).map { ImageInfo(MediaRef("/v/item-$it")) }

    private fun assertNotFileNotFound(items: List<ImageInfo>) {
        for (item in items) {
            assertNotEquals(
                ExportErrorCodes.FILE_NOT_FOUND,
                item.result?.code,
                "cancel must not map to FILE_NOT_FOUND (uri=${item.uri})",
            )
        }
    }

    private fun assertNoIng(items: List<ImageInfo>) {
        assertTrue(items.none { it.jobState is JobState.Ing }, "no item may remain Ing")
    }

    /** C1 — cancel before first item completes: zero successes; not saving; not FILE_NOT_FOUND. */
    @Test
    fun c1_cancelBeforeFirstItem_zeroSuccess_notSaving_notFileNotFound() = runBlocking {
        coroutineScope {
            val dir = tempDir("c1")
            val gate = CompletableDeferred<Unit>()
            val port = GatedSuccessPort(gate = gate)
            val session = newSession(dir, port)
            val items = batch(3)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = items))
            val selected = session.launchScreenUiStateFlow.value.selectedImageList

            val export = async { session.exportAndAwait(selected) }
            // Wait until port is blocked on first item.
            withTimeout(5_000) {
                while (port.received.isEmpty()) delay(5)
            }
            session.cancelExport()
            // Do not complete [gate]: cancelled waiter throws CancellationException without resume.
            export.await()

            val job = session.exportJobState.value
            assertFalse(job.isSaving, "isSaving must be false after cancel")
            assertTrue(job.isFinished)
            assertEquals(0, job.successCount)
            assertEquals(0, job.completedCount)
            assertTrue(selected.none { it.jobState is JobState.Success })
            assertNoIng(selected)
            assertNotFileNotFound(selected)
        }
    }

    /**
     * C2 — cancel after item 1 of N succeeds: successCount=1; remaining not success; not saving.
     */
    @Test
    fun c2_cancelAfterFirstSuccess_successCountOne_remainingNotSuccess() = runBlocking {
        coroutineScope {
            val dir = tempDir("c2")
            val afterFirst = CompletableDeferred<Unit>()
            val laterGate = CompletableDeferred<Unit>()
            val port = FirstThenHoldPort(afterFirst, laterGate)
            val session = newSession(dir, port)
            val items = batch(3)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = items))
            val selected = session.launchScreenUiStateFlow.value.selectedImageList

            val export = async { session.exportAndAwait(selected) }
            withTimeout(5_000) { afterFirst.await() }
            // First item finished; second is held.
            withTimeout(5_000) {
                while (port.received.size < 2) delay(5)
            }
            session.cancelExport()
            laterGate.complete(Unit)
            export.await()

            val job = session.exportJobState.value
            assertFalse(job.isSaving)
            assertTrue(job.isFinished)
            assertEquals(1, job.successCount)
            assertEquals(1, job.completedCount)
            assertEquals(1, selected.count { it.jobState is JobState.Success })
            assertTrue(selected.drop(1).none { it.jobState is JobState.Success })
            assertNoIng(selected)
            assertNotFileNotFound(selected)
            // At most first two entered the port; third must not succeed.
            assertTrue(port.received.size <= 2)
        }
    }

    /**
     * C3 — port throws CancellationException mid-item: not SourceDecode/FILE_NOT_FOUND; terminal clean.
     */
    @Test
    fun c3_portThrowsCancellationException_notMappedToFileNotFound() = runBlocking {
        val dir = tempDir("c3")
        val port = GatedSuccessPort(gate = null, throwCancelOnIndex = 0)
        val session = newSession(dir, port)
        val items = batch(2)
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = items))
        val selected = session.launchScreenUiStateFlow.value.selectedImageList

        session.exportAndAwait(selected)

        val job = session.exportJobState.value
        assertFalse(job.isSaving)
        assertTrue(job.isFinished)
        assertEquals(0, job.successCount)
        assertNoIng(selected)
        assertNotFileNotFound(selected)
        // In-flight cancel is CANCELLED taxonomy, not FILE_NOT_FOUND.
        val first = selected[0]
        assertIs<JobState.Failure>(first.jobState)
        assertEquals(ExportErrorCodes.CANCELLED, first.result?.code)
        // Second item never started → Ready (not success, not FILE_NOT_FOUND).
        assertIs<JobState.Ready>(selected[1].jobState)
        Unit
    }

    /**
     * C4 — retry after cancel re-runs only remaining (skips prior Success).
     *
     * Policy: Session preserves JobState.Success across startExport; only Ready/Failure are processed.
     */
    @Test
    fun c4_retryAfterCancel_skipsPriorSuccess_runsRemaining() = runBlocking {
        coroutineScope {
            val dir = tempDir("c4")
            val afterFirst = CompletableDeferred<Unit>()
            val laterGate = CompletableDeferred<Unit>()
            val port = FirstThenHoldPort(afterFirst, laterGate)
            val session = newSession(dir, port)
            val items = batch(2)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = items))
            val selected = session.launchScreenUiStateFlow.value.selectedImageList

            val firstExport = async { session.exportAndAwait(selected) }
            withTimeout(5_000) { afterFirst.await() }
            withTimeout(5_000) {
                while (port.received.size < 2) delay(5)
            }
            session.cancelExport()
            laterGate.complete(Unit)
            firstExport.await()

            assertEquals(1, selected.count { it.jobState is JobState.Success })
            val successUri = selected.first { it.jobState is JobState.Success }.uri
            val receivedAfterCancel = port.received.toList()

            // Retry full list: first Success skipped; remaining processed.
            session.exportAndAwait(selected)

            val job = session.exportJobState.value
            assertFalse(job.isSaving)
            assertTrue(job.isFinished)
            assertEquals(2, job.successCount)
            assertEquals(2, selected.count { it.jobState is JobState.Success })
            assertNoIng(selected)
            // Port must not receive the already-successful URI again on retry.
            val retryOnly = port.received.drop(receivedAfterCancel.size)
            assertTrue(
                retryOnly.none { it == successUri },
                "retry must not double-export success (retryOnly=$retryOnly success=$successUri)",
            )
            assertTrue(retryOnly.isNotEmpty(), "retry must process remaining items")
        }
    }
}
