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
 * Matrix:
 * select + applyOffset share list/cur identity; same-offset no-op; missing URI no-op;
 * stale offset on non-current URI does not flip cur.
 *
 * Driven only by Session APIs ([WatermarkSessionViewModel.applyOffset], [AppIntent.EnterEditor],
 * [AppIntent.SelectCurrent]).
 */
class SessionOffsetIdentityTest {

    private fun newSession(dir: File): WatermarkSessionViewModel {
        val waterRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(dir),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
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

    @Test
    fun applyOffset_firstAdjustedPhotoEstablishesDefaultAndPropagatesToUntouchedFollowers() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-follow-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            val c = ImageInfo(uri = MediaRef("file:///c.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a, b, c)))

            session.applyOffset(ImageInfo(uri = a.uri, offsetX = 0.18f, offsetY = 0.82f))
            var launch = session.launchScreenUiStateFlow.value
            assertEquals(a.uri, launch.defaultClampAnchorUri)
            assertEquals(0.18f, launch.defaultClampOffsetX)
            assertEquals(0.82f, launch.defaultClampOffsetY)
            assertEquals(listOf(0.18f, 0.18f, 0.18f), launch.selectedImageList.map { it.offsetX })
            assertEquals(listOf(0.82f, 0.82f, 0.82f), launch.selectedImageList.map { it.offsetY })
            assertTrue(launch.selectedImageList.none { it.hasCustomOffset })

            // Subsequent nudges on the anchor photo keep updating untouched followers.
            session.applyOffset(ImageInfo(uri = a.uri, offsetX = 0.24f, offsetY = 0.76f))
            launch = session.launchScreenUiStateFlow.value
            assertEquals(listOf(0.24f, 0.24f, 0.24f), launch.selectedImageList.map { it.offsetX })
            assertEquals(listOf(0.76f, 0.76f, 0.76f), launch.selectedImageList.map { it.offsetY })
            assertSame(launch.selectedImageList.first(), launch.curImageInfo)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun applyOffset_secondPhotoBecomesPerPhotoOverride_preservingAnchorAndUntouchedFollowers() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-override-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"))
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"))
            val c = ImageInfo(uri = MediaRef("file:///c.jpg"))
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a, b, c)))

            // Anchor on A
            session.applyOffset(ImageInfo(uri = a.uri, offsetX = 0.2f, offsetY = 0.2f))

            // Switch to B and customize it independently
            session.dispatchAndAwait(AppIntent.SelectCurrent(b.uri))
            session.applyOffset(ImageInfo(uri = b.uri, offsetX = 0.85f, offsetY = 0.15f))

            var launch = session.launchScreenUiStateFlow.value
            assertEquals(0.2f, launch.selectedImageList[0].offsetX)
            assertEquals(0.85f, launch.selectedImageList[1].offsetX)
            assertEquals(0.15f, launch.selectedImageList[1].offsetY)
            assertTrue(launch.selectedImageList[1].hasCustomOffset)
            assertEquals(0.2f, launch.selectedImageList[2].offsetX)
            assertTrue(!launch.selectedImageList[2].hasCustomOffset)
            assertSame(launch.selectedImageList[1], launch.curImageInfo)

            // Switching back to A and adjusting updates untouched C, but leaves customized B intact.
            session.dispatchAndAwait(AppIntent.SelectCurrent(a.uri))
            session.applyOffset(ImageInfo(uri = a.uri, offsetX = 0.35f, offsetY = 0.65f))

            launch = session.launchScreenUiStateFlow.value
            assertEquals(0.35f, launch.selectedImageList[0].offsetX)
            assertEquals(0.65f, launch.selectedImageList[0].offsetY)
            assertEquals(0.85f, launch.selectedImageList[1].offsetX)
            assertEquals(0.15f, launch.selectedImageList[1].offsetY)
            assertEquals(0.35f, launch.selectedImageList[2].offsetX)
            assertEquals(0.65f, launch.selectedImageList[2].offsetY)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun enterEditor_continuingSession_preservesOffsetsAndPropagatesDefaultToNewPhotos() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-append-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"))
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"))
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a, b)))

            session.applyOffset(ImageInfo(uri = a.uri, offsetX = 0.15f, offsetY = 0.85f))
            session.dispatchAndAwait(AppIntent.SelectCurrent(b.uri))
            session.applyOffset(ImageInfo(uri = b.uri, offsetX = 0.72f, offsetY = 0.28f))

            // Simulates iOS progressive import or "+ Add More Images" publishing a fresh list with default C.
            val freshA = ImageInfo(uri = a.uri)
            val freshB = ImageInfo(uri = b.uri)
            val c = ImageInfo(uri = MediaRef("file:///c.jpg"))
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(freshA, freshB, c)))

            val launch = session.launchScreenUiStateFlow.value
            assertEquals(0.15f, launch.selectedImageList[0].offsetX)
            assertEquals(0.85f, launch.selectedImageList[0].offsetY)
            assertEquals(0.72f, launch.selectedImageList[1].offsetX)
            assertEquals(0.28f, launch.selectedImageList[1].offsetY)
            assertTrue(launch.selectedImageList[1].hasCustomOffset)
            assertEquals(0.15f, launch.selectedImageList[2].offsetX)
            assertEquals(0.85f, launch.selectedImageList[2].offsetY)
            assertTrue(!launch.selectedImageList[2].hasCustomOffset)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun navigateBack_clearsSessionDefaultForNextBatch() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "sess-back-${System.nanoTime()}")
        try {
            val session = newSession(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"))
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(a)))
            session.applyOffset(ImageInfo(uri = a.uri, offsetX = 0.12f, offsetY = 0.91f))

            session.dispatchAndAwait(AppIntent.NavigateBack)
            val afterBack = session.launchScreenUiStateFlow.value
            assertEquals(null, afterBack.defaultClampOffsetX)
            assertEquals(null, afterBack.defaultClampOffsetY)
            assertEquals(null, afterBack.defaultClampAnchorUri)

            val x = ImageInfo(uri = MediaRef("file:///x.jpg"))
            session.dispatchAndAwait(AppIntent.EnterEditor(selected = listOf(x)))
            val nextBatch = session.launchScreenUiStateFlow.value
            assertEquals(0.5f, nextBatch.selectedImageList.single().offsetX)
            assertEquals(0.5f, nextBatch.selectedImageList.single().offsetY)
            assertSame(x, nextBatch.curImageInfo)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mergeLaunchPreservingLiveImages_propagatesLiveDefaultToNewlyAppendedPhoto() {
        val aUri = MediaRef("file:///a.jpg")
        val cUri = MediaRef("file:///c.jpg")
        val aBefore = ImageInfo(uri = aUri, offsetX = 0.5f, offsetY = 0.5f)
        val before = me.rosuh.easywatermark.ui.LaunchScreenState(
            uiState = me.rosuh.easywatermark.ui.LaunchScreenUiState.Editor,
            selectedImageList = listOf(aBefore),
            curImageInfo = aBefore,
        )
        val aLive = ImageInfo(uri = aUri, offsetX = 0.22f, offsetY = 0.77f, hasCustomOffset = false)
        val live = before.copy(
            selectedImageList = listOf(aLive),
            curImageInfo = aLive,
            defaultClampOffsetX = 0.22f,
            defaultClampOffsetY = 0.77f,
            defaultClampAnchorUri = aUri,
        )
        val cReduced = ImageInfo(uri = cUri)
        val reduced = before.copy(
            selectedImageList = listOf(aBefore, cReduced),
            curImageInfo = aBefore,
        )

        val merged = mergeLaunchPreservingLiveImages(reduced, live, before)
        assertEquals(0.22f, merged.selectedImageList[0].offsetX)
        assertEquals(0.22f, merged.selectedImageList[1].offsetX)
        assertEquals(0.77f, merged.selectedImageList[1].offsetY)
        assertSame(merged.selectedImageList[0], merged.curImageInfo)
        assertEquals(0.22f, merged.defaultClampOffsetX)
        assertEquals(0.77f, merged.defaultClampOffsetY)
        assertEquals(aUri, merged.defaultClampAnchorUri)
    }
}
