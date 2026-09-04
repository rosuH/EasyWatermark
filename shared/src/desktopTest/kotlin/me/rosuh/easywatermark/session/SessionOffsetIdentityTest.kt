package me.rosuh.easywatermark.session

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * E1 S1: Session owns list / current / offset identity.
 *
 * Matrix migrated from the product path of [me.rosuh.easywatermark.data.repo.WaterMarkOffsetUpdateTest]:
 * select + applyOffset share list/cur identity; same-offset no-op; missing URI no-op;
 * stale offset on non-current URI does not flip cur.
 *
 * Driven only by Session APIs ([WatermarkSessionViewModel.applyOffset], [AppIntent.EnterEditor],
 * [AppIntent.SelectCurrent]) — not [WaterMarkRepository.updateOffset].
 */
class SessionOffsetIdentityTest {

    private fun newSession(dir: File): WatermarkSessionViewModel {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(dir),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userRepo = UserConfigRepository(createUserConfigDataStore(dir))
        return WatermarkSessionViewModel(
            waterMarkRepo = waterRepo,
            userConfigRepo = userRepo,
            exportPipeline = null,
        )
    }

    @Test
    fun applyOffset_onlyChangesOffsets_preservesIngAndDims_doesNotMutateCaller() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-offset-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val existing = ImageInfo(
                uri = MediaRef("file:///x.jpg"),
                width = 1920,
                height = 1080,
                inSample = 2,
                scaleX = 1.5f,
                scaleY = 1.5f,
                result = null,
                jobState = JobState.Ing,
                isInDelModel = true,
                offsetX = 0.5f,
                offsetY = 0.5f,
            )
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(existing)))
            // waterMark collector may call resetJobStatus asynchronously; pin residual flags on the
            // Session list entry so applyOffset is proven to preserve *existing* list fields only.
            val listEntry = session.launchScreenUiStateFlow.value.selectedImageList.single()
            listEntry.jobState = JobState.Ing
            listEntry.isInDelModel = true
            listEntry.width = 1920
            listEntry.height = 1080
            listEntry.inSample = 2
            listEntry.scaleX = 1.5f
            listEntry.scaleY = 1.5f

            val staleUiCopy = ImageInfo(
                uri = existing.uri,
                width = 1,
                height = 1,
                jobState = JobState.Ready,
                isInDelModel = false,
                offsetX = 0.11f,
                offsetY = 0.22f,
            )

            session.applyOffset(staleUiCopy)

            assertEquals(JobState.Ready, staleUiCopy.jobState)
            assertEquals(1, staleUiCopy.width)

            val launch = session.launchScreenUiStateFlow.value
            val committed = launch.selectedImageList.single()
            assertNotSame(staleUiCopy, committed)
            assertEquals(0.11f, committed.offsetX)
            assertEquals(0.22f, committed.offsetY)
            assertEquals(1920, committed.width)
            assertEquals(1080, committed.height)
            assertEquals(2, committed.inSample)
            assertEquals(1.5f, committed.scaleX)
            assertTrue(committed.jobState is JobState.Ing)
            assertTrue(committed.isInDelModel)

            assertSame(committed, launch.curImageInfo)
            assertSame(committed, launch.selectedImageList.single())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyOffset_sameOffsets_returnsExistingListIdentity() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-same-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val existing = ImageInfo(
                uri = MediaRef("file:///same.jpg"),
                width = 10,
                height = 20,
                jobState = JobState.Ing,
                offsetX = 0.3f,
                offsetY = 0.4f,
            )
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(existing)))
            val listEntry = session.launchScreenUiStateFlow.value.selectedImageList.single()

            session.applyOffset(
                ImageInfo(uri = existing.uri, offsetX = 0.3f, offsetY = 0.4f),
            )

            val launch = session.launchScreenUiStateFlow.value
            assertSame(listEntry, launch.selectedImageList.single())
            assertSame(listEntry, launch.curImageInfo)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyOffset_staleA_doesNotRollbackSelectedB() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-sel-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a, b)))
            session.dispatchAndAwait(AppIntent.SelectCurrent(b.uri))
            assertEquals(b.uri, session.launchScreenUiStateFlow.value.curImageInfo?.uri)

            session.applyOffset(
                ImageInfo(uri = a.uri, offsetX = 0.1f, offsetY = 0.2f),
            )

            val launch = session.launchScreenUiStateFlow.value
            assertEquals(0.1f, launch.selectedImageList.first { it.uri == a.uri }.offsetX)
            // Selected must stay B.
            assertEquals(b.uri, launch.curImageInfo?.uri)
            assertEquals(0.5f, launch.curImageInfo?.offsetX)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyOffset_missingUri_isNoOp() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-miss-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a)))
            val before = session.launchScreenUiStateFlow.value.selectedImageList.single()

            session.applyOffset(
                ImageInfo(uri = MediaRef("file:///missing.jpg"), offsetX = 0.1f, offsetY = 0.1f),
            )

            val launch = session.launchScreenUiStateFlow.value
            assertSame(before, launch.selectedImageList.single())
            assertEquals(0.5f, launch.selectedImageList.single().offsetX)
            assertEquals(a.uri, launch.curImageInfo?.uri)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun enterEditor_select_applyOffset_listCurShareIdentity() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-chain-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val original = ImageInfo(
                uri = MediaRef("file:///chain.jpg"),
                offsetX = 0.5f,
                offsetY = 0.5f,
                width = 64,
            )
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(original)))
            session.dispatchAndAwait(AppIntent.SelectCurrent(original.uri))
            val before = session.launchScreenUiStateFlow.value
            assertSame(before.selectedImageList.single(), before.curImageInfo)

            session.applyOffset(
                ImageInfo(uri = original.uri, offsetX = 0.17f, offsetY = 0.83f),
            )

            val launch = session.launchScreenUiStateFlow.value
            val committed = requireNotNull(launch.curImageInfo)
            assertEquals(0.17f, committed.offsetX)
            assertEquals(0.83f, committed.offsetY)
            assertEquals(64, committed.width)
            assertSame(committed, launch.selectedImageList.single())
            assertNotSame(original, committed)
            Unit
        } finally {
            dir.deleteRecursively()
        }
    }
}
