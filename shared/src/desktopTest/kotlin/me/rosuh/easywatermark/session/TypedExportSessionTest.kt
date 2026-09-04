package me.rosuh.easywatermark.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D1 T1/T2: Session export loop consumes typed [ExportOutcome] on the production seam
 * ([WatermarkSessionViewModel.exportAndAwait] → [ExportPipelinePort.exportOne]).
 */
class TypedExportSessionTest {

    /** Returns fixed typed success without mutating [ImageInfo] (proves Session applies facts). */
    private class TypedSuccessPort(
        private val media: ExportedMedia,
    ) : ExportPipelinePort {
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome = ExportOutcome.success(media)
    }

    private class TaxonomySequencePort(
        private val outcomes: List<ExportOutcome>,
    ) : ExportPipelinePort {
        private var index = 0
        val received = mutableListOf<MediaRef>()
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            received += imageInfo.uri
            return outcomes[index++]
        }
    }

    /**
     * Holds the Session's first config collector until an export has completed. Later collectors
     * (including export's one-shot config read) receive the current value immediately.
     */
    private class DelayedInitialWatermarkStore : DataStore<Preferences> {
        private val collectorCount = AtomicInteger()
        private var current: Preferences = emptyPreferences()

        val initialCollectorStarted = CompletableDeferred<Unit>()
        val releaseInitialCollector = CompletableDeferred<Unit>()
        val initialCollectorHandled = CompletableDeferred<Unit>()

        override val data: Flow<Preferences> = flow {
            if (collectorCount.incrementAndGet() == 1) {
                initialCollectorStarted.complete(Unit)
                releaseInitialCollector.await()
                emit(current)
                initialCollectorHandled.complete(Unit)
            } else {
                emit(current)
            }
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            current = transform(current)
            return current
        }
    }

    private fun tempDir(name: String): File =
        File("build/d1-typed-session-$name-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun newSession(
        dir: File,
        port: ExportPipelinePort,
        waterMarkStore: DataStore<Preferences> = createWaterMarkDataStore(File(dir, "wm-store")),
    ): WatermarkSessionViewModel {
        val waterRepo = WaterMarkRepository(
            dataStore = waterMarkStore,
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

    @Test
    fun t1_sessionExport_recordsTypedMediaFactsFromPortReturn() = runBlocking {
        val dir = tempDir("t1-success")
        val media = ExportedMedia(
            ref = MediaRef("file:///exports/typed-out.png"),
            width = 640,
            height = 480,
            format = ImageFormat.PNG,
            byteCount = 42_000L,
        )
        val port = TypedSuccessPort(media)
        val session = newSession(dir, port)
        val item = ImageInfo(
            uri = MediaRef("/virtual/src.png"),
            width = 1,
            height = 1,
        )
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(item)))
        val selected = session.launchScreenUiStateFlow.value.selectedImageList
        assertEquals(1, selected.size)
        // Defaults must remain pre-export (port does not mutate).
        assertEquals(1, selected[0].width)
        assertEquals(1, selected[0].height)

        session.exportAndAwait(selected)

        assertIs<JobState.Success>(selected[0].jobState)
        // Session applies typed dims from ExportedMedia (sole source of truth when port skips mutation).
        assertEquals(640, selected[0].width)
        assertEquals(480, selected[0].height)
        // Legacy Result bridge carries MediaRef for hosts until D5.
        assertEquals("file:///exports/typed-out.png", (selected[0].result?.data as MediaRef).value)
        assertTrue(selected[0].result!!.isSuccess())
        val job = session.exportJobState.value
        assertTrue(job.isFinished)
        assertEquals(1, job.completedCount)
        assertEquals(1, job.totalCount)
        // Port return facts are complete (unit-level identity of the payload Session consumed).
        assertEquals(ImageFormat.PNG, media.format)
        assertEquals(42_000L, media.byteCount)
    }

    @Test
    fun t1_initialConfigSync_afterExport_doesNotResetTypedSuccess() = runBlocking {
        val dir = tempDir("t1-delayed-initial-config")
        val store = DelayedInitialWatermarkStore()
        val media = ExportedMedia(
            ref = MediaRef("file:///exports/typed-out.png"),
            width = 640,
            height = 480,
            format = ImageFormat.PNG,
            byteCount = 42_000L,
        )
        val session = newSession(
            dir = dir,
            port = TypedSuccessPort(media),
            waterMarkStore = store,
        )
        withTimeout(5_000) {
            store.initialCollectorStarted.await()
        }
        val item = ImageInfo(
            uri = MediaRef("/virtual/src-delayed-config.png"),
            width = 1,
            height = 1,
        )
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(item)))
        val selected = session.launchScreenUiStateFlow.value.selectedImageList

        session.exportAndAwait(selected)
        assertIs<JobState.Success>(selected.single().jobState)

        store.releaseInitialCollector.complete(Unit)
        withTimeout(5_000) {
            store.initialCollectorHandled.await()
        }

        assertIs<JobState.Success>(selected.single().jobState)
        assertTrue(session.exportJobState.value.isFinished)
    }

    @Test
    fun t2_portFailureTaxonomy_isDistinguishableInSessionBatch() = runBlocking {
        val dir = tempDir("t2-taxonomy")
        val port = TaxonomySequencePort(
            listOf(
                ExportOutcome.failure(ExportFailure.SourceDecode(message = "no source")),
                ExportOutcome.failure(ExportFailure.Encode(message = "encode false")),
                ExportOutcome.failure(ExportFailure.Cancelled(message = "cancelled")),
                ExportOutcome.success(
                    ExportedMedia(
                        ref = MediaRef("file:///ok.jpg"),
                        width = 10,
                        height = 10,
                        format = ImageFormat.JPEG,
                        byteCount = 100L,
                    ),
                ),
            ),
        )
        val session = newSession(dir, port)
        val batch = listOf(
            ImageInfo(MediaRef("/v/a")),
            ImageInfo(MediaRef("/v/b")),
            ImageInfo(MediaRef("/v/c")),
            ImageInfo(MediaRef("/v/d")),
        )
        session.dispatchAndAwait(AppIntent.EnterEditor(selected = batch))
        val selected = session.launchScreenUiStateFlow.value.selectedImageList
        session.exportAndAwait(selected)

        assertIs<JobState.Failure>(selected[0].jobState)
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, selected[0].result?.code)
        assertEquals("no source", selected[0].result?.message)

        assertIs<JobState.Failure>(selected[1].jobState)
        assertEquals(ExportErrorCodes.ENCODE, selected[1].result?.code)

        assertIs<JobState.Failure>(selected[2].jobState)
        assertEquals(ExportErrorCodes.CANCELLED, selected[2].result?.code)

        assertIs<JobState.Success>(selected[3].jobState)
        assertEquals("file:///ok.jpg", (selected[3].result?.data as MediaRef).value)

        assertEquals(1, session.exportJobState.value.completedCount)
        assertEquals(4, session.exportJobState.value.totalCount)
        assertEquals(4, port.received.size)
    }

    @Test
    fun desktopPort_missingSource_isSourceDecodeNotCollapsedStringOnly() = runBlocking {
        val dir = tempDir("desktop-decode")
        val port = DesktopExportPipelinePort(outputDirProvider = { dir })
        val outcome = port.exportOne(
            ImageInfo(MediaRef(File(dir, "missing.png").absolutePath)),
            WaterMark.default,
            UserPreferences.DEFAULT,
        )
        assertTrue(outcome.isFailure())
        val failure = (outcome as ExportOutcome.Failure).failure
        assertIs<ExportFailure.SourceDecode>(failure)
        assertEquals(ExportErrorCodes.FILE_NOT_FOUND, failure.legacyCode)
    }
}
