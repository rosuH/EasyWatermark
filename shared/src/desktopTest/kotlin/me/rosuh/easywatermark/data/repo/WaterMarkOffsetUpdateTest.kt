package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.JobState
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [WaterMarkRepository.updateOffset] is offset-only, pure-CAS, identity-safe.
 */
class WaterMarkOffsetUpdateTest {

    private fun newRepo(dir: File) = WaterMarkRepository(
        dataStore = createWaterMarkDataStore(dir),
        defaultTextProvider = { "EasyWatermark" },
        tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
        logError = {},
    )

    @Test
    fun updateOffset_onlyChangesOffsets_preservesIngAndDims_doesNotMutateCaller() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-offset-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
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
            repo.updateImageList(listOf(existing))
            repo.select(existing.uri)

            val staleUiCopy = ImageInfo(
                uri = existing.uri,
                width = 1,
                height = 1,
                jobState = JobState.Ready,
                isInDelModel = false,
                offsetX = 0.11f,
                offsetY = 0.22f,
            )

            val committed = requireNotNull(repo.updateOffset(staleUiCopy))

            assertEquals(JobState.Ready, staleUiCopy.jobState)
            assertEquals(1, staleUiCopy.width)
            assertNotSame(staleUiCopy, committed)

            assertEquals(0.11f, committed.offsetX)
            assertEquals(0.22f, committed.offsetY)
            assertEquals(1920, committed.width)
            assertEquals(1080, committed.height)
            assertEquals(2, committed.inSample)
            assertEquals(1.5f, committed.scaleX)
            assertTrue(committed.jobState is JobState.Ing)
            assertTrue(committed.isInDelModel)

            assertSame(committed, repo.imageInfoList.single())
            assertSame(committed, repo.selectedImage.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateOffset_sameOffsets_returnsExistingListIdentity() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-same-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
            val existing = ImageInfo(
                uri = MediaRef("file:///same.jpg"),
                width = 10,
                height = 20,
                jobState = JobState.Ing,
                offsetX = 0.3f,
                offsetY = 0.4f,
            )
            repo.updateImageList(listOf(existing))
            repo.select(existing.uri)
            val listEntry = repo.imageInfoList.single()

            val returned = requireNotNull(
                repo.updateOffset(
                    ImageInfo(uri = existing.uri, offsetX = 0.3f, offsetY = 0.4f),
                ),
            )
            assertSame(listEntry, returned)
            assertSame(returned, repo.imageInfoList.single())
            assertSame(returned, repo.selectedImage.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateOffset_staleA_doesNotRollbackSelectedB() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-sel-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            val b = ImageInfo(uri = MediaRef("file:///b.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            repo.updateImageList(listOf(a, b))
            repo.select(a.uri)
            repo.select(b.uri)
            assertEquals(b.uri, repo.selectedImage.value.uri)

            val committedA = requireNotNull(
                repo.updateOffset(
                    ImageInfo(uri = a.uri, offsetX = 0.1f, offsetY = 0.2f),
                ),
            )
            assertEquals(0.1f, committedA.offsetX)
            // Selected must stay B.
            assertEquals(b.uri, repo.selectedImage.value.uri)
            assertEquals(0.5f, repo.selectedImage.value.offsetX)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun updateOffset_missingUri_isNullNoOp() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-miss-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
            val a = ImageInfo(uri = MediaRef("file:///a.jpg"), offsetX = 0.5f, offsetY = 0.5f)
            repo.updateImageList(listOf(a))
            val before = repo.imageInfoList.single()

            val r = repo.updateOffset(
                ImageInfo(uri = MediaRef("file:///missing.jpg"), offsetX = 0.1f, offsetY = 0.1f),
            )
            assertNull(r)
            assertSame(before, repo.imageInfoList.single())
            assertEquals(0.5f, repo.imageInfoList.single().offsetX)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * List replace rebinds selected to the new list entry when URI still matches
     * (avoids same-URI old-instance residue after install).
     */
    @Test
    fun updateImageList_rebindsSelectedToNewListEntryWhenUriMatches() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-rebind-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
            val a1 = ImageInfo(
                uri = MediaRef("file:///a.jpg"),
                offsetX = 0.5f,
                offsetY = 0.5f,
                width = 10,
            )
            repo.updateImageList(listOf(a1))
            repo.select(a1.uri)
            assertSame(a1, repo.selectedImage.value)

            val a2 = ImageInfo(
                uri = a1.uri,
                offsetX = 0.2f,
                offsetY = 0.3f,
                width = 100,
            )
            repo.updateImageList(listOf(a2))
            assertSame(a2, repo.imageInfoList.single())
            assertSame(a2, repo.selectedImage.value)
            assertEquals(100, repo.selectedImage.value.width)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Install-then-select: when list was empty, select(first) must resolve to the installed
     * list entry (not a temporary ImageInfo(ref) that is a different object).
     */
    @Test
    fun updateImageList_thenSelect_sharesListEntryIdentity() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-install-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
            val first = ImageInfo(
                uri = MediaRef("file:///first.jpg"),
                offsetX = 0.5f,
                offsetY = 0.5f,
                width = 42,
            )
            // Empty list first: select-before-install would create ImageInfo(ref) temp.
            repo.updateImageList(listOf(first))
            repo.select(first.uri)
            assertSame(first, repo.imageInfoList.single())
            assertSame(first, repo.selectedImage.value)
            assertEquals(42, repo.selectedImage.value.width)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Sequential list → select → offset under Main confinement: list entry, selected, and
     * committed must be the **same** object with the new offsets. No cross-Main concurrency
     * is simulated — the contract is single-thread confinement of the three mutators.
     */
    @Test
    fun updateList_select_applyOffset_listSelectedCommittedShareIdentity() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "wm-chain-${System.nanoTime()}")
        try {
            val repo = newRepo(dir)
            val original = ImageInfo(
                uri = MediaRef("file:///chain.jpg"),
                offsetX = 0.5f,
                offsetY = 0.5f,
                width = 64,
            )
            repo.updateImageList(listOf(original))
            repo.select(original.uri)
            assertSame(original, repo.imageInfoList.single())
            assertSame(original, repo.selectedImage.value)

            val committed = requireNotNull(
                repo.updateOffset(
                    ImageInfo(uri = original.uri, offsetX = 0.17f, offsetY = 0.83f),
                ),
            )
            assertEquals(0.17f, committed.offsetX)
            assertEquals(0.83f, committed.offsetY)
            assertEquals(64, committed.width)
            assertSame(committed, repo.imageInfoList.single())
            assertSame(committed, repo.selectedImage.value)
            assertNotSame(original, committed)
        } finally {
            dir.deleteRecursively()
        }
    }
}
