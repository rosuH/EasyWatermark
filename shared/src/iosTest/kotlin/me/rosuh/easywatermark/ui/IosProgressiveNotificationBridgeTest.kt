@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package me.rosuh.easywatermark.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.session.IosAssetIdentityRegistry
import me.rosuh.easywatermark.session.IosPickGenerationGate
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.defaultIosAppServices
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral NotificationCenter bridge proof.
 *
 * Production observers deliver on Main; tests inject `notificationDeliveryQueue = null` so the
 * same notification names are deterministic without Photos/SwiftUI. fileReady is exercised via the
 * real production observer path (post FILE_READY → adopt → FILE_READY_RESULT), not a test seam
 * shortcut.
 */
class IosProgressiveNotificationBridgeTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val temporaryPaths = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        IosPickGenerationGate.resetForTests()
        IosAssetIdentityRegistry.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        temporaryPaths.forEach(IosSourceStager::deleteQuietly)
        IosAssetIdentityRegistry.resetForTests()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun beginViaNc_thenAdoptAcksPublished_andCloseRejectsFurtherEvents() =
        runTest(mainDispatcher) {
            val services = defaultIosAppServices()
            // Leave any prior singleton selection so this process-wide Session starts empty.
            services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)

            val controller = IosProgressiveImportController(
                session = services.session,
                waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
                hostScope = CoroutineScope(SupervisorJob() + mainDispatcher),
                hostAlive = { true },
                notificationDeliveryQueue = null,
            )
            val center = NSNotificationCenter.defaultCenter
            val generation = IosPickGenerationGate.nextPhotoGeneration()
            val importId = "one"
            val requestId = "request-one"
            val provisional = writeProvisionalJpeg()
            val ack = CompletableDeferred<Boolean>()
            val observer = center.addObserverForName(
                IosProgressiveImportController.FILE_READY_RESULT,
                null,
                null,
            ) { note ->
                val info = note?.userInfo ?: return@addObserverForName
                if (info[IosProgressiveImportController.KEY_REQUEST_ID]?.toString() == requestId) {
                    val okRaw = info[IosProgressiveImportController.KEY_OK]
                    val ok = when (okRaw) {
                        is Boolean -> okRaw
                        is Number -> okRaw.toInt() != 0
                        else -> false
                    }
                    ack.complete(ok)
                }
            }
            try {
                controller.installObservers()
                center.postNotificationName(
                    IosProgressiveImportController.BEGIN,
                    `object` = null,
                    userInfo = mapOf(
                        IosProgressiveImportController.KEY_GENERATION to
                            NSNumber.numberWithLong(generation),
                        IosProgressiveImportController.KEY_IMPORT_IDS to listOf(importId),
                        IosProgressiveImportController.KEY_APPEND to false,
                    ),
                )
                assertTrue(controller.slots.slot(importId) is EditorMediaSlot.Pending)

                // Production control plane: post FILE_READY through the same NC observer body.
                center.postNotificationName(
                    IosProgressiveImportController.FILE_READY,
                    `object` = null,
                    userInfo = mapOf(
                        IosProgressiveImportController.KEY_GENERATION to
                            NSNumber.numberWithLong(generation),
                        IosProgressiveImportController.KEY_IMPORT_ID to importId,
                        IosProgressiveImportController.KEY_PATH to provisional,
                        IosProgressiveImportController.KEY_REQUEST_ID to requestId,
                    ),
                )
                // hostScope.launch under UnconfinedTestDispatcher runs adoption eagerly.
                assertTrue(ack.await(), "fileReadyResult must report ok=true after Session publication")
                assertEquals(1, services.session.launchScreenUiStateFlow.value.selectedImageList.size)
                assertTrue(controller.slots.slot(importId) is EditorMediaSlot.Ready)
                val ownedWithoutId = services.session.launchScreenUiStateFlow.value
                    .selectedImageList.first().uri.value
                assertNull(
                    IosAssetIdentityRegistry.get(ownedWithoutId),
                    "fileReady without assetId must not write the identity registry",
                )

                controller.close()
                center.postNotificationName(
                    IosProgressiveImportController.BEGIN,
                    `object` = null,
                    userInfo = mapOf(
                        IosProgressiveImportController.KEY_GENERATION to
                            NSNumber.numberWithLong(generation + 1),
                        IosProgressiveImportController.KEY_IMPORT_IDS to listOf("late"),
                        IosProgressiveImportController.KEY_APPEND to false,
                    ),
                )
                assertEquals(-1L, controller.activeGeneration)
                assertTrue(controller.slots.slots.isEmpty())
                assertFalse(controller.importInProgress)
            } finally {
                center.removeObserver(observer)
                controller.close()
            }
        }

    @Test
    fun fileReadyWithAssetId_writesRegistryAfterSuccessfulAdopt() =
        runTest(mainDispatcher) {
            val services = defaultIosAppServices()
            services.session.dispatchAndAwait(me.rosuh.easywatermark.session.AppIntent.NavigateBack)

            val controller = IosProgressiveImportController(
                session = services.session,
                waterMarkProvider = { services.waterMarkRepo.waterMark.first() },
                hostScope = CoroutineScope(SupervisorJob() + mainDispatcher),
                hostAlive = { true },
                notificationDeliveryQueue = null,
            )
            val center = NSNotificationCenter.defaultCenter
            val generation = IosPickGenerationGate.nextPhotoGeneration()
            val importId = "with-id"
            val requestId = "request-with-id"
            val assetId = "PHAsset-test-1"
            val provisional = writeProvisionalJpeg()
            val ack = CompletableDeferred<Boolean>()
            val observer = center.addObserverForName(
                IosProgressiveImportController.FILE_READY_RESULT,
                null,
                null,
            ) { note ->
                val info = note?.userInfo ?: return@addObserverForName
                if (info[IosProgressiveImportController.KEY_REQUEST_ID]?.toString() == requestId) {
                    val okRaw = info[IosProgressiveImportController.KEY_OK]
                    val ok = when (okRaw) {
                        is Boolean -> okRaw
                        is Number -> okRaw.toInt() != 0
                        else -> false
                    }
                    ack.complete(ok)
                }
            }
            try {
                controller.installObservers()
                center.postNotificationName(
                    IosProgressiveImportController.BEGIN,
                    `object` = null,
                    userInfo = mapOf(
                        IosProgressiveImportController.KEY_GENERATION to
                            NSNumber.numberWithLong(generation),
                        IosProgressiveImportController.KEY_IMPORT_IDS to listOf(importId),
                        IosProgressiveImportController.KEY_APPEND to false,
                    ),
                )
                center.postNotificationName(
                    IosProgressiveImportController.FILE_READY,
                    `object` = null,
                    userInfo = mapOf(
                        IosProgressiveImportController.KEY_GENERATION to
                            NSNumber.numberWithLong(generation),
                        IosProgressiveImportController.KEY_IMPORT_ID to importId,
                        IosProgressiveImportController.KEY_PATH to provisional,
                        IosProgressiveImportController.KEY_REQUEST_ID to requestId,
                        IosProgressiveImportController.KEY_ASSET_ID to assetId,
                    ),
                )
                assertTrue(ack.await())
                val owned = services.session.launchScreenUiStateFlow.value
                    .selectedImageList.first().uri.value
                assertTrue(owned.contains("ewm_src_"))
                assertEquals(assetId, IosAssetIdentityRegistry.get(owned))
                assertEquals(owned, IosAssetIdentityRegistry.pathFor(assetId))
            } finally {
                center.removeObserver(observer)
                controller.close()
                services.session.dispatchAndAwait(
                    me.rosuh.easywatermark.session.AppIntent.NavigateBack,
                )
            }
        }

    private fun writeProvisionalJpeg(): String {
        val surface = Surface.makeRasterN32Premul(8, 8)
        surface.canvas.clear(0xFF336699.toInt())
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG)!!.bytes
        val path = NSTemporaryDirectory() + "ewm_import_provisional_" + NSUUID().UUIDString
        assertTrue(IosByteArrayInterop.toNSData(bytes).writeToFile(path, atomically = true))
        temporaryPaths += path
        return path
    }
}
