package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.LaunchScreenState
import me.rosuh.easywatermark.ui.LaunchScreenUiState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Offset→export ordering, pure CAS merge pin, effect-order (awaited), and repo identity.
 * Production APIs + capturing [ExportPipelinePort]; 5s timeout.
 *
 * Does **not** claim fire-and-forget [WatermarkSessionViewModel.dispatch] FIFO:
 * [Mutex] only serializes reduce+effects of one intent vs another; tests await each intent.
 */
class OffsetExportOrderingTest {

    private class CapturingExportPort : ExportPipelinePort {
        val received = CopyOnWriteArrayList<ImageInfo>()

        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): Result<MediaRef> {
            received.add(imageInfo)
            return Result.success(MediaRef("file://export/${imageInfo.uri.value}"))
        }
    }

    private fun newSession(
        dir: File,
        port: CapturingExportPort = CapturingExportPort(),
    ): Triple<WatermarkSessionViewModel, CapturingExportPort, WaterMarkRepository> {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(dir),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userRepo = UserConfigRepository(createUserConfigDataStore(dir))
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterRepo,
            userConfigRepo = userRepo,
            exportPipeline = port,
        )
        return Triple(session, port, waterRepo)
    }

    @Test
    fun applyOffset_lateStaleSync_thenExport_keepsNewOffsetsAndResult() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "offset-export-${System.nanoTime()}")
        try {
            val (session, port, waterRepo) = newSession(dir)
            val original = ImageInfo(
                uri = MediaRef("file:///photo-a.jpg"),
                offsetX = 0.5f,
                offsetY = 0.5f,
            )
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(original)))
            val staleHostList = session.launchScreenUiStateFlow.value.selectedImageList
            val oldSnapshot = original.copy(offsetX = 0.5f, offsetY = 0.5f)
            val dragged = original.copy(offsetX = 0.12f, offsetY = 0.88f)

            session.applyOffset(dragged)
            assertEquals(0.12f, session.launchScreenUiStateFlow.value.selectedImageList.single().offsetX)
            // Repo is offset truth; session and repo share the committed instance after applyOffset.
            val committed = waterRepo.imageInfoList.single()
            assertEquals(0.12f, committed.offsetX)
            assertSame(committed, session.launchScreenUiStateFlow.value.selectedImageList.single())

            session.dispatchAndAwait(AppIntent.SyncCurrentImage(oldSnapshot))
            assertEquals(0.12f, session.launchScreenUiStateFlow.value.curImageInfo?.offsetX)
            assertEquals(0.12f, session.launchScreenUiStateFlow.value.selectedImageList.single().offsetX)

            session.requestExport(staleHostList)
            withTimeout(5_000) {
                while (!session.exportJobState.value.isFinished) {
                    kotlinx.coroutines.yield()
                }
            }

            val exported = port.received.single()
            assertEquals(0.12f, exported.offsetX)
            assertEquals(0.88f, exported.offsetY)

            val after = session.launchScreenUiStateFlow.value.selectedImageList.single()
            assertEquals(0.12f, after.offsetX)
            assertNotNull(after.result)
            assertTrue(after.result!!.isSuccess())
            assertTrue(after.jobState is JobState.Success)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Pure merge pin: reduced snapshot based on [before] must not win over a concurrent [live]
     * that already has new offsets (models final CAS update { merge(reduced, current, before) }).
     */
    @Test
    fun mergeLaunchPreservingLiveImages_prefersLiveOffsetsOverStaleReduced() {
        val uri = MediaRef("file:///m.jpg")
        val beforeItem = ImageInfo(uri = uri, offsetX = 0.5f, offsetY = 0.5f)
        val liveItem = ImageInfo(uri = uri, offsetX = 0.2f, offsetY = 0.8f)
        val before = LaunchScreenState(
            uiState = LaunchScreenUiState.Editor,
            selectedImageList = listOf(beforeItem),
            curImageInfo = beforeItem,
            waterMark = WaterMark.default,
        )
        val live = before.copy(
            selectedImageList = listOf(liveItem),
            curImageInfo = liveItem,
        )
        // Reduced as if SyncWaterMark only touched waterMark, based on before.
        val reduced = before.copy(waterMark = WaterMark.default.copy(text = "x"))
        val merged = mergeLaunchPreservingLiveImages(reduced, live, before)
        assertEquals(0.2f, merged.selectedImageList.single().offsetX)
        assertEquals(0.8f, merged.selectedImageList.single().offsetY)
        assertEquals(0.2f, merged.curImageInfo?.offsetX)
        assertEquals("x", merged.waterMark.text)
    }

    /**
     * Production UI enters editor, then selects current. Await each intent so reduce+effects
     * complete; do not rely on fire-and-forget dispatch FIFO (Mutex is not a queue).
     */
    @Test
    fun enterEditor_thenSelectCurrent_finalIsB() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "offset-fx-${System.nanoTime()}")
        try {
            val (session, _, waterRepo) = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"), offsetX = 0.5f, offsetY = 0.5f)

            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a, b)))
            // After EnterEditor: list installed first, selected is list first entry (same identity).
            assertSame(waterRepo.imageInfoList.first(), waterRepo.selectedImage.value)
            assertEquals(a.uri, waterRepo.selectedImage.value.uri)

            session.dispatchAndAwait(AppIntent.SelectCurrent(b.uri))

            assertEquals(b.uri, session.launchScreenUiStateFlow.value.curImageInfo?.uri)
            assertEquals(b.uri, waterRepo.selectedImage.value.uri)
            assertSame(
                waterRepo.imageInfoList.first { it.uri == b.uri },
                waterRepo.selectedImage.value,
            )
            assertEquals(2, session.launchScreenUiStateFlow.value.selectedImageList.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyOffset_missingUri_doesNotInstallCallerAsCur() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "offset-miss-${System.nanoTime()}")
        try {
            val (session, _, _) = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///keep.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a)))
            session.applyOffset(
                ImageInfo(uri = MediaRef("file:///ghost.jpg"), offsetX = 0.1f, offsetY = 0.1f),
            )
            assertEquals(a.uri, session.launchScreenUiStateFlow.value.curImageInfo?.uri)
            assertEquals(0.5f, session.launchScreenUiStateFlow.value.selectedImageList.single().offsetX)
            assertEquals(1, session.launchScreenUiStateFlow.value.selectedImageList.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
