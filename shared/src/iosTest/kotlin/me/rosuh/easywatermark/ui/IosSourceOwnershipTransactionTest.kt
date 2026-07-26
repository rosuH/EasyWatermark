@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package me.rosuh.easywatermark.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import kotlinx.coroutines.CancellationException
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.IosPickGenerationGate
import me.rosuh.easywatermark.session.IosHostOwnershipProbe
import me.rosuh.easywatermark.session.IosPickPublishProbe
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.IosStageWriteProbe
import me.rosuh.easywatermark.session.StalePickGenerationException
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * L1: transactional iOS `ewm_src_*` ownership —
 * replacement deletes superseded temps after success; failures delete unpublished temps.
 *
 * Uses [UnconfinedTestDispatcher] as Main so hostScope work does not hang in native tests.
 */
class IosSourceOwnershipTransactionTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
        IosPickPublishProbe.clear()
        IosStageWriteProbe.clear()
        IosHostOwnershipProbe.clear()
    }

    private fun isolatedServices(): Pair<IosAppServices, ViewModelStore> {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "l1_own_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "l1_own_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = null,
        )
        val store = ViewModelStore()
        store.put("l1-own-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return services to store
    }

    private fun ewmSrcPathsInTemp(): Set<String> {
        val dir = NSTemporaryDirectory()
        val raw = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as? List<*>
            ?: emptyList<Any>()
        return raw.mapNotNull { name ->
            val n = name as? String ?: return@mapNotNull null
            if (n.startsWith("ewm_src_")) dir + n else null
        }.toSet()
    }

    @Test
    fun stagePickedImagesBytes_staleGeneration_deletesUnpublishedTemps() = runTest {
        val (services, store) = isolatedServices()
        try {
            val before = ewmSrcPathsInTemp()
            val gen = IosPickGenerationGate.nextPhotoGeneration()
            // Invalidate generation before publish by minting a newer token.
            IosPickGenerationGate.nextPhotoGeneration()
            try {
                services.stagePickedImagesBytes(
                    imageBytesList = listOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
                    append = false,
                    pickGeneration = gen,
                )
                fail("expected StalePickGenerationException")
            } catch (_: StalePickGenerationException) {
                // expected
            }
            val after = ewmSrcPathsInTemp()
            val leaked = after - before
            assertTrue(
                leaked.isEmpty(),
                "stale stage must not retain unpublished temps: leaked=$leaked",
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun deliverPickedPhotosBatch_replace_deletesSupersededOwnedTemps() = runTest {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val first = IosSourceStager.stageBytes(byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2))
            host.trackOwnedStagedPathForTests(first)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(first))

            val gen = IosPickGenerationGate.nextPhotoGeneration()
            host.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1)),
                append = false,
                renderPreview = false,
                pickGeneration = gen,
            )
            assertFalse(
                NSFileManager.defaultManager.fileExistsAtPath(first),
                "replacement must delete superseded owned ewm_src after success",
            )
            val owned = host.ownedStagedPathsForTests()
            assertTrue(owned.isNotEmpty())
            assertFalse(first in owned)
            owned.forEach { path ->
                assertTrue(path.contains("ewm_src_"))
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
            }
            host.dispose()
        } finally {
            store.clear()
        }
    }

    @Test
    fun deliverPickedPhotosBatch_append_preservesPriorOwnedTemps() = runTest {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val firstGen = IosPickGenerationGate.nextPhotoGeneration()
            host.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)),
                append = false,
                renderPreview = false,
                pickGeneration = firstGen,
            )
            val afterFirst = host.ownedStagedPathsForTests()
            assertTrue(afterFirst.size == 1)
            val firstPath = afterFirst.single()
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(firstPath))

            val secondGen = IosPickGenerationGate.nextPhotoGeneration()
            host.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(3, 3, 3, 3, 3, 3, 3, 3)),
                append = true,
                renderPreview = false,
                pickGeneration = secondGen,
            )
            val afterAppend = host.ownedStagedPathsForTests()
            assertTrue(firstPath in afterAppend, "append must preserve prior owned path")
            assertTrue(afterAppend.size >= 2)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(firstPath))
            host.dispose()
        } finally {
            store.clear()
        }
    }

    @Test
    fun stageBytesBounded_partialMultiItemFailure_deletesSuccessfulSiblings() = runTest {
        val (services, store) = isolatedServices()
        try {
            val before = ewmSrcPathsInTemp()
            val gen = IosPickGenerationGate.nextPhotoGeneration()
            try {
                // Second item empty → stageBytes require fails after first sibling may have written.
                services.stagePickedImagesBytes(
                    imageBytesList = listOf(
                        byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                        byteArrayOf(), // empty fails inside IosSourceStager.stageBytes
                    ),
                    append = false,
                    pickGeneration = gen,
                )
                fail("expected failure on empty image")
            } catch (_: Throwable) {
                // expected (IllegalArgumentException / other)
            }
            val after = ewmSrcPathsInTemp()
            val leaked = after - before
            assertTrue(
                leaked.isEmpty(),
                "partial multi-item stage failure must delete successful siblings: leaked=$leaked",
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun stageBytesBounded_cancelAfterWrite_beforeTrack_stillCleansPath() = runTest {
        val (services, store) = isolatedServices()
        try {
            val before = ewmSrcPathsInTemp()
            val gen = IosPickGenerationGate.nextPhotoGeneration()
            // Force a post-write probe CancellationException before ownership registration would
            // normally complete; NonCancellable track + outer cleanup must still delete the file.
            IosStageWriteProbe.install {
                throw CancellationException("force cancel after write before track")
            }
            try {
                services.stagePickedImagesBytes(
                    imageBytesList = listOf(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
                    append = false,
                    pickGeneration = gen,
                )
                fail("expected CancellationException")
            } catch (_: CancellationException) {
                // expected
            } catch (_: Throwable) {
                // outer may wrap; still require no leak
            }
            val after = ewmSrcPathsInTemp()
            val leaked = after - before
            assertTrue(
                leaked.isEmpty(),
                "cancel after write must still register+cleanup path: leaked=$leaked",
            )
        } finally {
            IosStageWriteProbe.clear()
            store.clear()
        }
    }

    @Test
    fun disposedHost_beforeGuardedPublish_doesNotMutateSession_andDeletesStaged() = runTest {
        val (services, store) = isolatedServices()
        try {
            // Live selection established by host A.
            val hostA = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val liveGen = IosPickGenerationGate.nextPhotoGeneration()
            hostA.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(4, 4, 4, 4, 4, 4, 4, 4)),
                append = false,
                renderPreview = false,
                pickGeneration = liveGen,
            )
            val livePath = hostA.ownedStagedPathsForTests().single()
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(livePath))
            val sessionBefore = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }

            // Host B will stage then dispose at guarded publish boundary.
            val hostB = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val beforeTemps = ewmSrcPathsInTemp()
            IosPickPublishProbe.install {
                hostB.dispose()
            }
            val rejectGen = IosPickGenerationGate.nextPhotoGeneration()
            try {
                hostB.deliverPickedPhotosBatch(
                    images = listOf(byteArrayOf(7, 7, 7, 7, 7, 7, 7, 7)),
                    append = false,
                    renderPreview = false,
                    pickGeneration = rejectGen,
                )
            } catch (_: StalePickGenerationException) {
                // expected when hostAlive fails at publish
            } catch (_: Throwable) {
                // any failure is fine if Session/temps assertions hold
            } finally {
                IosPickPublishProbe.clear()
            }

            assertTrue(hostB.isDisposedForTests())
            val sessionAfter = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
            assertEquals(sessionBefore, sessionAfter)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(livePath))
            val afterTemps = ewmSrcPathsInTemp()
            val newTemps = afterTemps - beforeTemps
            val sessionSet = sessionAfter.toSet()
            assertTrue(
                newTemps.all { it in sessionSet },
                "no path becomes untracked after disposed-host reject: newTemps=$newTemps session=$sessionSet",
            )
            hostA.dispose()
        } finally {
            IosPickPublishProbe.clear()
            store.clear()
        }
    }

    @Test
    fun disposedHost_afterPublish_beforeOwnership_revertsSession_andDeletesIntroduced() = runTest {
        val (services, store) = isolatedServices()
        try {
            val hostA = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val liveGen = IosPickGenerationGate.nextPhotoGeneration()
            hostA.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(4, 4, 4, 4, 4, 4, 4, 4)),
                append = false,
                renderPreview = false,
                pickGeneration = liveGen,
            )
            val livePath = hostA.ownedStagedPathsForTests().single()
            val sessionBefore = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
            assertEquals(listOf(livePath), sessionBefore)

            val hostB = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val beforeTemps = ewmSrcPathsInTemp()
            // Dispose in the post-publication / pre-ownership-registration window only.
            IosHostOwnershipProbe.install {
                hostB.dispose()
            }
            val lateGen = IosPickGenerationGate.nextPhotoGeneration()
            try {
                hostB.deliverPickedPhotosBatch(
                    images = listOf(byteArrayOf(8, 8, 8, 8, 8, 8, 8, 8)),
                    append = false,
                    renderPreview = false,
                    pickGeneration = lateGen,
                )
            } finally {
                IosHostOwnershipProbe.clear()
            }

            assertTrue(hostB.isDisposedForTests())
            // Session must be restored to host A selection — no new selection left by disposed B.
            val sessionAfter = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
            assertEquals(sessionBefore, sessionAfter)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(livePath))
            // Introduced temps from rejected ownership adopt must not remain untracked.
            val afterTemps = ewmSrcPathsInTemp()
            val newTemps = afterTemps - beforeTemps
            val sessionSet = sessionAfter.toSet()
            assertTrue(
                newTemps.all { it in sessionSet },
                "post-publish dispose must not leave untracked temps: newTemps=$newTemps session=$sessionSet",
            )
            // hostB must not own any tracked paths after dispose.
            assertTrue(hostB.ownedStagedPathsForTests().isEmpty())
            hostA.dispose()
        } finally {
            IosHostOwnershipProbe.clear()
            store.clear()
        }
    }

    @Test
    fun sameHost_replace_lateDispose_doesNotRestoreDeletedPrevious_andNoTempLeak() = runTest {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val firstGen = IosPickGenerationGate.nextPhotoGeneration()
            host.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1)),
                append = false,
                renderPreview = false,
                pickGeneration = firstGen,
            )
            val previousPath = host.ownedStagedPathsForTests().single()
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(previousPath))
            val beforeTemps = ewmSrcPathsInTemp()

            // Same host replace: dispose after B publishes deletes previousOwned (A) before adopt.
            IosHostOwnershipProbe.install {
                host.dispose()
            }
            val replaceGen = IosPickGenerationGate.nextPhotoGeneration()
            try {
                host.deliverPickedPhotosBatch(
                    images = listOf(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2)),
                    append = false,
                    renderPreview = false,
                    pickGeneration = replaceGen,
                )
            } finally {
                IosHostOwnershipProbe.clear()
            }

            assertTrue(host.isDisposedForTests())
            // Dispose deleted previous path — Session must NOT be restored to that dead file.
            assertFalse(
                NSFileManager.defaultManager.fileExistsAtPath(previousPath),
                "same-host dispose must delete previously owned ewm_src",
            )
            val sessionAfter = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
            assertFalse(
                previousPath in sessionAfter,
                "Session must not point at deleted previous path: session=$sessionAfter",
            )
            // Every remaining Session path must still exist on disk.
            sessionAfter.forEach { path ->
                if (path.contains("ewm_src_")) {
                    assertTrue(
                        NSFileManager.defaultManager.fileExistsAtPath(path),
                        "Session ewm_src must remain readable: $path",
                    )
                }
            }
            // No new untracked temps from the aborted replace delivery.
            val afterTemps = ewmSrcPathsInTemp()
            val newTemps = afterTemps - beforeTemps
            val sessionSet = sessionAfter.toSet()
            assertTrue(
                newTemps.all { it in sessionSet },
                "late same-host dispose must not leak temps: newTemps=$newTemps session=$sessionSet",
            )
            assertTrue(host.ownedStagedPathsForTests().isEmpty())
        } finally {
            IosHostOwnershipProbe.clear()
            store.clear()
        }
    }

    @Test
    fun newerGeneration_wins_duringLateAdopt_leavesNewerSelection_andCleansOlderOnly() = runTest {
        val (services, store) = isolatedServices()
        try {
            val hostA = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val liveGen = IosPickGenerationGate.nextPhotoGeneration()
            hostA.deliverPickedPhotosBatch(
                images = listOf(byteArrayOf(3, 3, 3, 3, 3, 3, 3, 3)),
                append = false,
                renderPreview = false,
                pickGeneration = liveGen,
            )
            val livePath = hostA.ownedStagedPathsForTests().single()

            val hostB = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val beforeTemps = ewmSrcPathsInTemp()
            var newerPath: String? = null
            // After B publishes, a newer generation publishes C, then B is disposed.
            IosHostOwnershipProbe.install {
                val newerGen = IosPickGenerationGate.nextPhotoGeneration()
                newerPath = services.stagePickedImagesBytes(
                    imageBytesList = listOf(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)),
                    append = false,
                    pickGeneration = newerGen,
                )
                hostB.dispose()
            }
            val lateGen = IosPickGenerationGate.nextPhotoGeneration()
            try {
                hostB.deliverPickedPhotosBatch(
                    images = listOf(byteArrayOf(5, 5, 5, 5, 5, 5, 5, 5)),
                    append = false,
                    renderPreview = false,
                    pickGeneration = lateGen,
                )
            } finally {
                IosHostOwnershipProbe.clear()
            }

            val newer = requireNotNull(newerPath)
            assertTrue(hostB.isDisposedForTests())
            // Newer generation must remain the Session selection and stay readable.
            val sessionAfter = services.session.launchScreenUiStateFlow.value.selectedImageList
                .map { it.uri.value }
            assertEquals(listOf(newer), sessionAfter)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(newer))
            // Older live path may still be owned by hostA; cleanup must not delete the newer path.
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(livePath))
            // B's staged temps must not leak untracked (not in Session).
            val afterTemps = ewmSrcPathsInTemp()
            val newTemps = afterTemps - beforeTemps
            val sessionSet = sessionAfter.toSet()
            assertTrue(
                newTemps.all { it in sessionSet || it == livePath },
                "older delivery cleanup must not leave untracked temps: newTemps=$newTemps session=$sessionSet",
            )
            // Explicitly: B must not leave Session rewritten away from newer.
            assertFalse(hostB.ownedStagedPathsForTests().any { it !in sessionSet && NSFileManager.defaultManager.fileExistsAtPath(it) })
            hostA.dispose()
            // After hostA dispose, newer path (not owned by A) must still exist if Session holds it.
            // Note: hostA dispose only deletes hostA-owned paths (livePath), not newer.
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(newer))
            // Clean the process-wide newer path left by services.stage (not host-tracked).
            IosSourceStager.deleteQuietly(newer)
        } finally {
            IosHostOwnershipProbe.clear()
            store.clear()
        }
    }
}
