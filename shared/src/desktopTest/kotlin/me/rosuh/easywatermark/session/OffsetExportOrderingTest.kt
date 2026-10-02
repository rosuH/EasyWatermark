package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
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
 * Offset→export ordering, pure CAS merge pin, awaited selection, and Session identity.
 * Production APIs + capturing [ExportPipelinePort]; 5s timeout.
 *
 * Does **not** claim fire-and-forget [WatermarkSessionViewModel.dispatch] FIFO:
 * The session mutex serializes publication; tests await each intent.
 */
class OffsetExportOrderingTest {

    private class CapturingExportPort : ExportPipelinePort {
        val received = CopyOnWriteArrayList<ImageInfo>()

        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            received.add(imageInfo)
            return ExportOutcome.success(
                me.rosuh.easywatermark.data.model.ExportedMedia(
                    ref = MediaRef("file://export/${imageInfo.uri.value}"),
                    width = 1,
                    height = 1,
                    format = me.rosuh.easywatermark.data.model.ImageFormat.JPEG,
                    byteCount = 1L,
                ),
            )
        }
    }

    private fun newSession(
        dir: File,
        port: CapturingExportPort = CapturingExportPort(),
    ): Pair<WatermarkSessionViewModel, CapturingExportPort> {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(dir),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
        )
        val userRepo = UserConfigRepository(createUserConfigDataStore(dir))
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterRepo,
            userConfigRepo = userRepo,
            exportPipeline = port,
        )
        return session to port
    }

    @Test
    fun applyOffset_configSync_thenExportFromStaleList_keepsNewOffsetsAndResult() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "offset-export-${System.nanoTime()}")
        try {
            val (session, port) = newSession(dir)
            val original = ImageInfo(
                uri = MediaRef("file:///photo-a.jpg"),
                offsetX = 0.5f,
                offsetY = 0.5f,
            )
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(original)))
            val staleHostList = session.launchScreenUiStateFlow.value.selectedImageList
            val dragged = original.copy(offsetX = 0.12f, offsetY = 0.88f)

            session.applyOffset(dragged)
            assertEquals(0.12f, session.launchScreenUiStateFlow.value.selectedImageList.single().offsetX)
            // Session is offset truth (list + cur share identity).
            val sessionCommitted = session.launchScreenUiStateFlow.value.selectedImageList.single()
            assertEquals(0.12f, sessionCommitted.offsetX)
            assertSame(sessionCommitted, session.launchScreenUiStateFlow.value.curImageInfo)

            // Config publication must preserve the latest Session offsets.
            session.dispatchAndAwait(AppIntent.SyncWaterMark(WaterMark.default.copy(text = "changed")))
            assertEquals(0.12f, session.launchScreenUiStateFlow.value.curImageInfo?.offsetX)
            assertEquals(0.12f, session.launchScreenUiStateFlow.value.selectedImageList.single().offsetX)

            // Export resolves the stale host list against the current Session snapshot.
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
 * That already has new offsets (models final CAS update { merge(reduced, current, before) }).     */
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
     * Production UI awaits editor entry and selection before immediately exporting focus.
     */
    @Test
    fun enterEditor_thenSelectCurrent_immediateExportUsesB() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "offset-fx-${System.nanoTime()}")
        try {
            val (session, port) = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"), offsetX = 0.5f, offsetY = 0.5f)

            session.nextSelectedPos = 1
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a, b)))
            val entered = session.launchScreenUiStateFlow.value
            assertSame(entered.selectedImageList.first(), entered.curImageInfo)
            assertEquals(a.uri, entered.curImageInfo?.uri)
            assertEquals(0, session.nextSelectedPos)

            session.dispatchAndAwait(AppIntent.SelectCurrent(b.uri))
            val selected = session.launchScreenUiStateFlow.value
            assertSame(selected.selectedImageList.last(), selected.curImageInfo)
            assertEquals(b.uri, selected.curImageInfo?.uri)
            assertEquals(2, selected.selectedImageList.size)
            session.exportAndAwait(listOf(requireNotNull(selected.curImageInfo)))
            assertEquals(b.uri, port.received.single().uri)

        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun selectionPublication_resetsPositionOnlyForAcceptedBatch() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "selection-pos-${System.nanoTime()}")
        try {
            val (session, _) = newSession(dir)
            val image = ImageInfo(MediaRef("file:///picked.jpg"))
            session.nextSelectedPos = 7
            session.dispatchAndAwait(AppIntent.EnterEditor(emptyList()))
            assertEquals(7, session.nextSelectedPos)
            assertTrue(!session.publishEditorSelectionIf({ false }, listOf(image), WaterMark.default))
            assertEquals(7, session.nextSelectedPos)
            assertTrue(session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())

            assertTrue(session.publishEditorSelectionIf({ true }, listOf(image), WaterMark.default))
            assertEquals(0, session.nextSelectedPos)
            assertSame(image, session.launchScreenUiStateFlow.value.curImageInfo)

            val gallery = me.rosuh.easywatermark.ui.Image(
                id = 1, uri = image.uri, name = "picked", size = 1, date = 0, check = false,
            )
            session.nextSelectedPos = 7
            session.dispatchAndAwait(AppIntent.GalleryLoaded(listOf(gallery)))
            session.dispatchAndAwait(AppIntent.DismissGallery(selected = true))
            assertEquals(7, session.nextSelectedPos)
            session.dispatchAndAwait(AppIntent.ToggleGalleryItem(gallery, index = 0, checked = true))
            session.dispatchAndAwait(AppIntent.DismissGallery(selected = true))
            assertEquals(0, session.nextSelectedPos)
            val launch = session.launchScreenUiStateFlow.value
            assertSame(launch.selectedImageList.single(), launch.curImageInfo)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyOffset_missingUri_doesNotInstallCallerAsCur() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "offset-miss-${System.nanoTime()}")
        try {
            val (session, _) = newSession(dir)
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
