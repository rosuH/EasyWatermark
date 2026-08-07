@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package me.rosuh.easywatermark.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.ImportTimelineProbe
import me.rosuh.easywatermark.session.IosPickGenerationGate
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.defaultIosAppServices
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.numberWithLong
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral progressive adoption using the same Main harness as staging identity tests
 * (setMain + UnconfinedTestDispatcher + runTest).
 */
class IosProgressiveAdoptionTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val temps = mutableListOf<String>()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
        IosPickGenerationGate.resetForTests()
        ImportTimelineProbe.reset()
    }

    @AfterTest
    fun tearDown() {
        temps.forEach(IosSourceStager::deleteQuietly)
        temps.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun fileReady_publishesOnlyWhenGenerationCurrent_andAcksTruthfully() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            controller.beginForTests(gen, listOf("a", "b"), append = false)
            val path = writeProvisionalJpeg()
            val ack = controller.noteFileReadyForTests(gen, "a", path)
            assertEquals(IosProgressiveImportController.AdoptionAck.Published, ack)
            assertEquals(1, services.session.launchScreenUiStateFlow.value.selectedImageList.size)
            assertTrue(controller.slots.slot("a") is EditorMediaSlot.Ready)

            val stale = gen + 100
            val path2 = writeProvisionalJpeg()
            val staleAck = controller.noteFileReadyForTests(stale, "b", path2)
            assertEquals(IosProgressiveImportController.AdoptionAck.StaleGeneration, staleAck)
            assertEquals(1, services.session.launchScreenUiStateFlow.value.selectedImageList.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun removeLastReady_navigatesBack() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            controller.beginForTests(gen, listOf("only"), append = false)
            val path = writeProvisionalJpeg()
            assertEquals(
                IosProgressiveImportController.AdoptionAck.Published,
                controller.noteFileReadyForTests(gen, "only", path),
            )
            val stablePath = services.session.launchScreenUiStateFlow.value
                .selectedImageList
                .single()
                .uri
                .value
            assertTrue(controller.removeSlot("only"))
            assertTrue(services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())
            // After NavigateBack Session no longer holds the path — owned ewm_src is deleted.
            assertFalse(
                NSFileManager.defaultManager.fileExistsAtPath(stablePath),
                "last-remove must delete unheld ewm_src after Session clear",
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun partialAndAllFailedSlots_retryWithoutPublishingFakeSessionItems() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            controller.beginForTests(gen, listOf("a", "b"), append = false)
            assertTrue(controller.noteFileFailedForTests(gen, "a", "iCloud unavailable"))
            assertTrue(controller.slots.slot("a") is EditorMediaSlot.Failed)
            assertTrue(services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())

            controller.requestRetry("a")
            assertTrue(controller.slots.slot("a") is EditorMediaSlot.Pending)
            assertEquals(
                IosProgressiveImportController.AdoptionAck.Published,
                controller.noteFileReadyForTests(gen, "a", writeProvisionalJpeg()),
            )
            assertEquals(1, controller.slots.readyImagesInOrder().size)
            assertEquals(1, services.session.launchScreenUiStateFlow.value.selectedImageList.size)
        } finally {
            controller.close()
        }
    }

    @Test
    fun undecodableInput_failsClosed_neverPublishesReady() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            controller.beginForTests(gen, listOf("bad"), append = false)
            val garbage = NSTemporaryDirectory() + "ewm_import_provisional_" + NSUUID().UUIDString
            assertTrue(
                IosByteArrayInterop.toNSData(byteArrayOf(0x00, 0x01, 0x02, 0x03))
                    .writeToFile(garbage, atomically = true),
            )
            temps += garbage
            val ack = controller.noteFileReadyForTests(gen, "bad", garbage)
            assertEquals(IosProgressiveImportController.AdoptionAck.Error, ack)
            assertTrue(controller.slots.slot("bad") is EditorMediaSlot.Failed)
            assertTrue(services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun controllerClose_doesNotDeleteSessionHeldSource() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        controller.beginForTests(gen, listOf("keep"), append = false)
        assertEquals(
            IosProgressiveImportController.AdoptionAck.Published,
            controller.noteFileReadyForTests(gen, "keep", writeProvisionalJpeg()),
        )
        val stablePath = services.session.launchScreenUiStateFlow.value.selectedImageList.single().uri.value
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(stablePath))
        controller.close()
        // Session still holds the path across Host rebuild — file must remain readable.
        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(stablePath),
            "close must not delete Session-referenced ewm_src",
        )
        assertEquals(
            listOf(stablePath),
            services.session.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value },
        )
        // Cleanup after explicit Session leave (simulates product remove/navigate).
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        IosSourceStager.deleteQuietly(stablePath)
    }

    @Test
    fun disposedOrDeadHost_rejectsAdoption_andDoesNotPublish() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        controller.beginForTests(gen, listOf("a"), append = false)
        controller.close()
        assertEquals(
            IosProgressiveImportController.AdoptionAck.Disposed,
            controller.noteFileReadyForTests(gen, "a", writeProvisionalJpeg()),
        )
        assertTrue(
            services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty(),
            "closed controller must not publish",
        )

        // Replacement host can still publish under a new gen.
        val gen2 = IosPickGenerationGate.nextPhotoGeneration()
        val next = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            next.beginForTests(gen2, listOf("b"), append = false)
            assertEquals(
                IosProgressiveImportController.AdoptionAck.Published,
                next.noteFileReadyForTests(gen2, "b", writeProvisionalJpeg()),
            )
            val afterPublish = services.session.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value }
            assertTrue(afterPublish.isNotEmpty())
            assertTrue(next.slots.slot("b") is EditorMediaSlot.Ready)

            // hostAlive=false rejects without replacing the replacement host's Ready set.
            val gen3 = IosPickGenerationGate.nextPhotoGeneration()
            val dead = IosProgressiveImportController(
                session = services.session,
                waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
                hostScope = scope,
                hostAlive = { false },
                notificationDeliveryQueue = null,
            )
            try {
                dead.beginForTests(gen3, listOf("c"), append = false)
                assertEquals(
                    IosProgressiveImportController.AdoptionAck.Disposed,
                    dead.noteFileReadyForTests(gen3, "c", writeProvisionalJpeg()),
                )
                assertEquals(
                    afterPublish,
                    services.session.launchScreenUiStateFlow.value.selectedImageList.map { it.uri.value },
                    "dead host must not replace prior published Ready selection",
                )
            } finally {
                dead.close()
            }
        } finally {
            next.close()
        }
    }

    @Test
    fun lastRemove_thenStaleRepoEmission_doesNotReinjectDeletedUri() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            controller.beginForTests(gen, listOf("only"), append = false)
            assertEquals(
                IosProgressiveImportController.AdoptionAck.Published,
                controller.noteFileReadyForTests(gen, "only", writeProvisionalJpeg()),
            )
            val stablePath = services.session.launchScreenUiStateFlow.value.selectedImageList.single().uri.value
            // Simulate repo still holding the URI after leave-editor (E3 residual mirror).
            services.waterMarkRepo.select(MediaRef(stablePath))
            assertTrue(controller.removeSlot("only"))
            assertTrue(services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(stablePath))
            // Stale SyncCurrentImage must not re-inject deleted path into Session (A12 E1).
            services.session.dispatchAndAwait(AppIntent.SyncCurrentImage(ImageInfo(MediaRef(stablePath))))
            val after = services.session.launchScreenUiStateFlow.value
            assertTrue(after.selectedImageList.isEmpty())
            assertTrue(
                after.curImageInfo == null || after.curImageInfo?.uri == MediaRef.Empty,
                "empty Session must not re-bind deleted ewm_src via SyncCurrentImage",
            )
        } finally {
            controller.close()
        }
    }

    @Test
    fun timeline_recordsEndToEndSpineWithTimestamps() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        ImportTimelineProbe.reset(gen)
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
            onReadyPublished = { focus ->
                // Simulate host visible-pixel / preview publication boundaries (privacy-safe ids).
                ImportTimelineProbe.mark("first_visible_placeholder", gen, "focus")
                ImportTimelineProbe.mark("first_filmstrip_pixels", gen, "cell")
                ImportTimelineProbe.mark("first_watermarked_preview", gen, "preview")
            },
        )
        try {
            // Production BEGIN path marks picker_callback → slots_published.
            controller.installObservers()
            NSNotificationCenter.defaultCenter.postNotificationName(
                IosProgressiveImportController.BEGIN,
                `object` = null,
                userInfo = mapOf(
                    IosProgressiveImportController.KEY_GENERATION to
                        NSNumber.numberWithLong(gen),
                    IosProgressiveImportController.KEY_IMPORT_IDS to listOf("a"),
                    IosProgressiveImportController.KEY_APPEND to false,
                ),
            )
            assertEquals(
                IosProgressiveImportController.AdoptionAck.Published,
                controller.noteFileReadyForTests(gen, "a", writeProvisionalJpeg()),
            )
            val required = listOf(
                "picker_callback",
                "slots_published",
                "file_ready",
                "session_adopted",
                "first_visible_placeholder",
                "first_filmstrip_pixels",
                "first_watermarked_preview",
            )
            assertTrue(
                ImportTimelineProbe.containsTimelineInOrder(required),
                "timeline=${ImportTimelineProbe.formatTimeline()}",
            )
            val snap = ImportTimelineProbe.snapshot()
            assertTrue(snap.isNotEmpty())
            // Monotonic non-decreasing timestamps; no path-like importId tokens.
            assertTrue(snap.zipWithNext().all { (a, b) -> b.monoMs >= a.monoMs })
            assertTrue(snap.none { it.importId.contains('/') || it.importId.contains("ewm_src_") })
            // Captured deltas for artifacts (printed into test log → verification harvest).
            println("A14_TIMELINE\n${ImportTimelineProbe.formatTimeline()}")
        } finally {
            controller.close()
        }
    }

    @Test
    fun preBodyCancellation_acksExactlyOnce_andDoesNotPublish() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        val requestId = "prebody-1"
        val ackCount = kotlin.concurrent.AtomicInt(0)
        val ackOk = kotlin.concurrent.AtomicInt(-1)
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(
            IosProgressiveImportController.FILE_READY_RESULT,
            null,
            null,
        ) { note ->
            val info = note?.userInfo ?: return@addObserverForName
            if (info[IosProgressiveImportController.KEY_REQUEST_ID]?.toString() != requestId) return@addObserverForName
            ackCount.addAndGet(1)
            val okRaw = info[IosProgressiveImportController.KEY_OK]
            val ok = when (okRaw) {
                is Boolean -> okRaw
                is Number -> okRaw.toInt() != 0
                else -> false
            }
            ackOk.value = if (ok) 1 else 0
        }
        try {
            controller.beginForTests(gen, listOf("a"), append = false)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            controller.preBodyGateForTests = gate
            val provisional = writeProvisionalJpeg()
            val job = controller.launchProductionFileReadyJobForTests(
                generation = gen,
                importId = "a",
                provisionalPath = provisional,
                requestId = requestId,
            )
            // Cancel before body acquires the mutex / starts adopt.
            job.cancel()
            // Unblock any race where the job still reaches await after cancel.
            gate.complete(Unit)
            job.join()
            assertEquals(1, ackCount.value, "exactly one ACK for pre-body cancel")
            assertEquals(0, ackOk.value, "ACK must report failure")
            assertTrue(services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())
            assertTrue(controller.slots.slot("a") is EditorMediaSlot.Pending)
        } finally {
            center.removeObserver(observer)
            controller.preBodyGateForTests = null
            controller.close()
        }
    }

    @Test
    fun postStableCopyCancellation_rollsBackDestinationAndDoesNotPublish() =
        runTest(mainDispatcher) {
            val services = defaultIosAppServices()
            services.session.dispatchAndAwait(AppIntent.NavigateBack)
            val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
            val gen = IosPickGenerationGate.nextPhotoGeneration()
            val controller = IosProgressiveImportController(
                session = services.session,
                waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
                hostScope = scope,
                hostAlive = { true },
                notificationDeliveryQueue = null,
            )
            try {
                controller.beginForTests(gen, listOf("a"), append = false)
                val provisional = writeProvisionalJpeg()
                var capturedStable: String? = null
                val jobHolder = arrayOfNulls<kotlinx.coroutines.Job>(1)
                controller.afterStableCopyForTests = { stable ->
                    capturedStable = stable
                    // Cancel the adoption job immediately after NonCancellable copy.
                    jobHolder[0]?.cancel()
                }
                val job = scope.launch {
                    val ack = controller.noteFileReadyForTests(gen, "a", provisional)
                    assertEquals(
                        IosProgressiveImportController.AdoptionAck.StaleGeneration,
                        ack,
                        "cancelled after stable copy must not publish",
                    )
                }
                jobHolder[0] = job
                job.join()
                assertTrue(capturedStable != null, "stable path must have been produced")
                assertFalse(
                    NSFileManager.defaultManager.fileExistsAtPath(capturedStable!!),
                    "stable destination must be rolled back after post-copy cancel",
                )
                assertTrue(services.session.launchScreenUiStateFlow.value.selectedImageList.isEmpty())
                assertTrue(controller.slots.slot("a") is EditorMediaSlot.Pending)
            } finally {
                controller.afterStableCopyForTests = null
                controller.close()
            }
        }

    @Test
    fun fiftyPathProgressiveAdoption_doesNotRetainByteArraysOnSession() = runTest(mainDispatcher) {
        val services = defaultIosAppServices()
        services.session.dispatchAndAwait(AppIntent.NavigateBack)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val gen = IosPickGenerationGate.nextPhotoGeneration()
        val controller = IosProgressiveImportController(
            session = services.session,
            waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
            hostScope = scope,
            hostAlive = { true },
            notificationDeliveryQueue = null,
        )
        try {
            val ids = (0 until 50).map { "i$it" }
            controller.beginForTests(gen, ids, append = false)
            // Publish 50 ready paths — Session holds MediaRef paths only (no ByteArray list).
            for (id in ids) {
                assertEquals(
                    IosProgressiveImportController.AdoptionAck.Published,
                    controller.noteFileReadyForTests(gen, id, writeProvisionalJpeg()),
                )
            }
            val selected = services.session.launchScreenUiStateFlow.value.selectedImageList
            assertEquals(50, selected.size)
            assertTrue(selected.all { it.uri.value.contains("ewm_src_") })
            // Structural: ImageInfo has no payload field — only path refs.
            assertTrue(selected.all { it.width > 0 && it.height > 0 })
        } finally {
            controller.close()
            services.session.dispatchAndAwait(AppIntent.NavigateBack)
        }
    }

    private fun writeProvisionalJpeg(): String {
        val surface = Surface.makeRasterN32Premul(8, 8)
        surface.canvas.clear(0xFF336699.toInt())
        val image = surface.makeImageSnapshot()
        val bytes = image.encodeToData(EncodedImageFormat.JPEG)!!.bytes
        val path = NSTemporaryDirectory() + "ewm_import_provisional_" + NSUUID().UUIDString
        assertTrue(IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true))
        temps += path
        return path
    }
}
