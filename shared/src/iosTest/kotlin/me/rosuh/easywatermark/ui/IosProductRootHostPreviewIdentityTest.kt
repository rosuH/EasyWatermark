@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package me.rosuh.easywatermark.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Result
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import me.rosuh.easywatermark.session.ExportOutcome
import me.rosuh.easywatermark.session.ExportPipelinePort
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.IosAssetIdentityRegistry
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue 26 / C4.4R.S1 — host-level production seams:
 * - F3: [IosProductRootHost.deliverPickedPhotosBatch] fresh A→B preview-cache identity
 * - F7: real Session export path after fresh A→B receives B's staged MediaRef, never A
 *
 * Not a PHPicker runtime proof.
 */
class IosProductRootHostPreviewIdentityTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** F7: records export source identity without running a second renderer matrix. */
    private class RecordingExportPort : ExportPipelinePort {
        val receivedUris = mutableListOf<String>()
        override suspend fun exportOne(
            imageInfo: ImageInfo,
            config: WaterMark,
            prefs: UserPreferences,
        ): ExportOutcome {
            receivedUris += imageInfo.uri.value
            imageInfo.width = 10
            imageInfo.height = 10
            return ExportOutcome.success(
                me.rosuh.easywatermark.data.model.ExportedMedia(
                    ref = MediaRef("file://export-identity/${receivedUris.size}"),
                    width = 10,
                    height = 10,
                    format = me.rosuh.easywatermark.data.model.ImageFormat.JPEG,
                    byteCount = 1L,
                ),
            )
        }
    }

    private class Graph(
        val services: IosAppServices,
        val exportPort: RecordingExportPort,
        private val viewModelStore: ViewModelStore,
        private val stagedPaths: MutableList<String> = mutableListOf(),
    ) {
        fun trackSessionPaths() {
            val launch = services.session.launchScreenUiStateFlow.value
            stagedPaths += launch.selectedImageList.map { it.uri.value }
        }

        fun close() {
            viewModelStore.clear()
            stagedPaths.distinct().forEach { IosSourceStager.deleteQuietly(it) }
            stagedPaths.clear()
        }
    }

    private fun isolatedGraph(): Graph {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "c44rs1_host_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "c44rs1_host_uc_$id"),
        )
        val exportPort = RecordingExportPort()
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = exportPort,
        )
        val store = ViewModelStore()
        store.put("c44rs1-host-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return Graph(services, exportPort, store)
    }

    /**
     * hostScope uses [Dispatchers.Main] (Darwin). After a gated inject resumes,
     * pump real time so that queue can paint; [runTest] virtual delay will not.
     */
    private suspend fun pumpUntil(
        timeout: kotlin.time.Duration = 2.seconds,
        predicate: () -> Boolean,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (!predicate() && deadline.hasNotPassedNow()) {
            withContext(Dispatchers.Default) { delay(15) }
        }
    }

    private fun solidPng(color: Color): ByteArray {
        val w = 64
        val h = 48
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return IosWatermarkRenderer.encodePng(bmp)
    }

    @Test
    fun k1_host_fresh_b_clears_caches_and_binds_preview_source_to_b() =
        runTest(mainDispatcher.scheduler) {
            val graph = isolatedGraph()
            try {
                val host = IosProductRootHost(
                    onPickPhoto = {},
                    onPickIcon = {},
                    onShare = {},
                    onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                    services = graph.services,
                )
                try {
                    val aBytes = solidPng(Color(0xFF203040))
                    val bBytes = solidPng(Color(0xFFE0A040))

                    me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                    val g1 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                    host.deliverPickedPhotosBatch(
                        images = listOf(aBytes),
                        append = false,
                        renderPreview = true,
                        pickGeneration = g1,
                    )
                    graph.trackSessionPaths()
                    val afterA = host.previewIdentityForTests()
                    val pathA = graph.services.session.launchScreenUiStateFlow.first()
                        .curImageInfo?.uri?.value
                    assertNotNull(pathA, "Session focus after A")
                    assertEquals(
                        pathA,
                        afterA.previewSourcePath,
                        "host previewSourcePath must bind to A after fresh deliver A",
                    )
                    assertTrue(
                        pathA in afterA.placeholderCachePaths || afterA.previewSourcePath == pathA,
                        "A placeholder or preview path must be present after deliver A",
                    )

                    val g2 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                    host.deliverPickedPhotosBatch(
                        images = listOf(bBytes),
                        append = false,
                        renderPreview = true,
                        pickGeneration = g2,
                    )
                    graph.trackSessionPaths()
                    val launchB = graph.services.session.launchScreenUiStateFlow.first()
                    val pathB = launchB.curImageInfo?.uri?.value
                    assertNotNull(pathB, "Session focus after B")
                    assertNotEquals(pathA, pathB, "fresh B mints a new staged path")
                    assertEquals(listOf(pathB), launchB.selectedImageList.map { it.uri.value })

                    val afterB = host.previewIdentityForTests()
                    assertEquals(
                        pathB,
                        afterB.previewSourcePath,
                        "host previewSourcePath must bind to B after fresh deliver B",
                    )
                    assertFalse(
                        pathA in afterB.wmCachePaths,
                        "A must not remain in wmPreviewCache after fresh B",
                    )
                    assertFalse(
                        pathA in afterB.placeholderCachePaths,
                        "A must not remain in sourcePlaceholderCache after fresh B",
                    )
                    assertFalse(
                        pathA == afterB.previewSourcePath,
                        "displayed preview must not still claim path A",
                    )
                } finally {
                    host.dispose()
                }
            } finally {
                graph.close()
            }
        }

    /**
     * F7 — real Session export path after fresh A→B must request B's staged source, never A.
     * Uses [RecordingExportPort] (identity only; encode/write owned by Port tests).
     */
    @Test
    fun k1_host_fresh_b_export_source_is_b_never_a() = runTest(mainDispatcher.scheduler) {
        val graph = isolatedGraph()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            try {
                val aBytes = solidPng(Color(0xFF203040))
                val bBytes = solidPng(Color(0xFFE0A040))

                me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                val g1 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(aBytes),
                    append = false,
                    renderPreview = false,
                    pickGeneration = g1,
                )
                graph.trackSessionPaths()
                val pathA = graph.services.session.launchScreenUiStateFlow.first()
                    .curImageInfo?.uri?.value
                assertNotNull(pathA)

                val g2 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(bBytes),
                    append = false,
                    renderPreview = false,
                    pickGeneration = g2,
                )
                graph.trackSessionPaths()
                val launchB = graph.services.session.launchScreenUiStateFlow.first()
                val pathB = launchB.curImageInfo?.uri?.value
                assertNotNull(pathB)
                assertNotEquals(pathA, pathB)
                assertEquals(pathB, launchB.selectedImageList.single().uri.value)

                // Real Session export seam (exportAndAwait → ExportPipelinePort.exportOne).
                val focus = launchB.curImageInfo ?: launchB.selectedImageList.first()
                graph.services.session.exportAndAwait(listOf(focus))

                assertEquals(
                    listOf(pathB),
                    graph.exportPort.receivedUris,
                    "export port must receive B's staged MediaRef only",
                )
                assertFalse(
                    pathA in graph.exportPort.receivedUris,
                    "A must never be export source after fresh B",
                )
            } finally {
                host.dispose()
            }
        } finally {
            graph.close()
        }
    }

    @Test
    fun p3_library_derivative_is_not_written_to_wm_cache() = runTest(mainDispatcher) {
        val graph = isolatedGraph()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            try {
                me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                IosAssetIdentityRegistry.resetForTests()
                val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(solidPng(Color(0xFF336699))),
                    append = false,
                    renderPreview = true,
                    pickGeneration = gen,
                )
                graph.trackSessionPaths()
                val path = graph.services.session.launchScreenUiStateFlow.first()
                    .curImageInfo?.uri?.value
                assertNotNull(path)
                IosAssetIdentityRegistry.put(path, "p3-fast-id")
                val unique = ImageBitmap(33, 31, ImageBitmapConfig.Argb8888)
                host.installPhotoKitFastPathForTests { _, _ -> unique }
                host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
                val identity = host.previewIdentityForTests()
                assertTrue(path in identity.wmCachePaths || identity.previewSourcePath == path)
                val wmSize = host.watermarkedCachedSizeForTests(path)
                if (wmSize != null) {
                    assertFalse(
                        wmSize == 33 to 31,
                        "PhotoKit 33x31 frame must not be the Watermarked cache entry",
                    )
                }
                assertFalse(path in identity.placeholderCachePaths && wmSize == 33 to 31)
            } finally {
                host.installPhotoKitFastPathForTests(null)
                host.dispose()
            }
        } finally {
            IosAssetIdentityRegistry.resetForTests()
            graph.close()
        }
    }

    @Test
    fun p3_miss_and_timeout_keep_today_hit_class() = runTest(mainDispatcher) {
        suspend fun runSwitch(
            withId: Boolean,
            producer: (suspend (String, Int) -> ImageBitmap?)?,
        ): String {
            val graph = isolatedGraph()
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            return try {
                me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                IosAssetIdentityRegistry.resetForTests()
                val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(solidPng(Color(0xFF112233))),
                    append = false,
                    renderPreview = false,
                    pickGeneration = gen,
                )
                graph.trackSessionPaths()
                val path = graph.services.session.launchScreenUiStateFlow.value
                    .curImageInfo?.uri?.value
                    ?: error("expected staged path")
                if (withId) {
                    IosAssetIdentityRegistry.put(path, "p3-miss-id")
                    host.installPhotoKitFastPathForTests(producer)
                }
                host.switchImageAndAwaitForTests(path, awaitNeighbors = false).hit
            } finally {
                host.installPhotoKitFastPathForTests(null)
                host.dispose()
                IosAssetIdentityRegistry.resetForTests()
                graph.close()
            }
        }
        val today = runSwitch(withId = false, producer = null)
        val miss = runSwitch(withId = true, producer = { _, _ -> null })
        assertEquals(today, miss, "null PhotoKit producer must not change hit class")
    }

    @Test
    fun p3_wm_cache_hit_does_not_call_photokit() = runTest(mainDispatcher) {
        val graph = isolatedGraph()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            try {
                me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                IosAssetIdentityRegistry.resetForTests()
                val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(solidPng(Color(0xFF445566))),
                    append = false,
                    renderPreview = true,
                    pickGeneration = gen,
                )
                graph.trackSessionPaths()
                val path = graph.services.session.launchScreenUiStateFlow.first()
                    .curImageInfo?.uri?.value
                assertNotNull(path)
                val cached = ImageBitmap(24, 24, ImageBitmapConfig.Argb8888)
                host.putPlaceholderForTests(path, cached)
                IosAssetIdentityRegistry.put(path, "p3-wm-hit")
                var calls = 0
                host.installPhotoKitFastPathForTests { _, _ ->
                    calls += 1
                    ImageBitmap(8, 8, ImageBitmapConfig.Argb8888)
                }
                val timing = host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
                assertTrue(
                    timing.hit == "source" || host.previewIdentityForTests().overlayPresent,
                    timing.hit,
                )
                assertEquals(0, calls, "cached Source + cell compose must not start PhotoKit")
            } finally {
                host.installPhotoKitFastPathForTests(null)
                host.dispose()
            }
        } finally {
            IosAssetIdentityRegistry.resetForTests()
            graph.close()
        }
    }

    /**
     * Attempt-1 used `previewGen != capturedGen` as stale. Cold switch launches PhotoKit
     * then immediately bumps gen for same-path ImageIO, so a producer that returns after
     * that bump was no-op'd. This gated inject completes only after the bump.
     */
    @Test
    fun p3_same_switch_preview_gen_bump_still_paints_library_derivative() =
        runTest(mainDispatcher) {
            val graph = isolatedGraph()
            try {
                val host = IosProductRootHost(
                    onPickPhoto = {},
                    onPickIcon = {},
                    onShare = {},
                    onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                    services = graph.services,
                )
                try {
                    me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                    IosAssetIdentityRegistry.resetForTests()
                    val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                    host.deliverPickedPhotosBatch(
                        images = listOf(solidPng(Color(0xFF669933))),
                        append = false,
                        renderPreview = false,
                        pickGeneration = gen,
                    )
                    graph.trackSessionPaths()
                    val path = graph.services.session.launchScreenUiStateFlow.first()
                        .curImageInfo?.uri?.value
                    assertNotNull(path)
                    IosAssetIdentityRegistry.put(path, "p3-gen-bump-id")
                    // ImageIO of this path must not win the race and trip the
                    // "Watermarked already showing" drop. Attempt-1 still fails
                    // because switch joins only after previewGen++.
                    IosSourceStager.deleteQuietly(path)
                    val waiting = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val produced = CompletableDeferred<Unit>()
                    val unique = ImageBitmap(33, 31, ImageBitmapConfig.Argb8888)
                    host.installPhotoKitFastPathForTests { _, _ ->
                        waiting.complete(Unit)
                        release.await()
                        unique.also { produced.complete(Unit) }
                    }
                    val switchJob = launch {
                        host.switchImageAndAwaitForTests(path, awaitNeighbors = false)
                    }
                    waiting.await()
                    switchJob.join()
                    release.complete(Unit)
                    pumpUntil { produced.isCompleted }
                    assertTrue(produced.isCompleted, "PhotoKit inject must resume after gen bump")
                    pumpUntil { host.previewIdentityForTests().libraryDerivativePath == path }
                    val identity = host.previewIdentityForTests()
                    assertEquals(
                        path,
                        identity.libraryDerivativePath,
                        "same-switch ImageIO previewGen++ must not drop this path's Library derivative",
                    )
                    assertEquals(33 to 31, identity.libraryDerivativeSize)
                    assertTrue(
                        identity.overlayPresent,
                        "Library may paint only as the photo layer under a matching overlay",
                    )
                } finally {
                    host.installPhotoKitFastPathForTests(null)
                    host.dispose()
                }
            } finally {
                IosAssetIdentityRegistry.resetForTests()
                graph.close()
            }
        }

    @Test
    fun p3_late_photokit_for_previous_path_is_dropped() = runTest(mainDispatcher) {
        val graph = isolatedGraph()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            try {
                me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
                IosAssetIdentityRegistry.resetForTests()
                val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(solidPng(Color(0xFF112211)), solidPng(Color(0xFF334433))),
                    append = false,
                    renderPreview = false,
                    pickGeneration = gen,
                )
                graph.trackSessionPaths()
                val paths = graph.services.session.launchScreenUiStateFlow.first()
                    .selectedImageList.map { it.uri.value }
                assertEquals(2, paths.size)
                val pathA = paths[0]
                val pathB = paths[1]
                IosAssetIdentityRegistry.put(pathA, "p3-late-a")
                IosAssetIdentityRegistry.put(pathB, "p3-late-b")
                val aWaiting = CompletableDeferred<Unit>()
                val aRelease = CompletableDeferred<Unit>()
                val aProduced = CompletableDeferred<Unit>()
                host.installPhotoKitFastPathForTests { assetId, _ ->
                    if (assetId == "p3-late-a") {
                        aWaiting.complete(Unit)
                        aRelease.await()
                        aProduced.complete(Unit)
                        ImageBitmap(33, 31, ImageBitmapConfig.Argb8888)
                    } else {
                        ImageBitmap(17, 19, ImageBitmapConfig.Argb8888)
                    }
                }
                val switchA = launch {
                    host.switchImageAndAwaitForTests(pathA, awaitNeighbors = false)
                }
                aWaiting.await()
                host.switchImageAndAwaitForTests(pathB, awaitNeighbors = false)
                aRelease.complete(Unit)
                pumpUntil { aProduced.isCompleted }
                assertTrue(aProduced.isCompleted, "late A PhotoKit must still resume to prove drop")
                pumpUntil {
                    val id = host.previewIdentityForTests()
                    id.libraryDerivativePath != pathA && aProduced.isCompleted
                }
                switchA.join()
                val identity = host.previewIdentityForTests()
                assertNotEquals(
                    pathA,
                    identity.libraryDerivativePath,
                    "late PhotoKit for a previous focus path must not paint",
                )
                if (identity.libraryDerivativeSize != null) {
                    assertNotEquals(33 to 31, identity.libraryDerivativeSize)
                }
            } finally {
                host.installPhotoKitFastPathForTests(null)
                host.dispose()
            }
        } finally {
            IosAssetIdentityRegistry.resetForTests()
            graph.close()
        }
    }
}
