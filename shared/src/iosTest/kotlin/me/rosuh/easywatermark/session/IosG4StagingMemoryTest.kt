@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package me.rosuh.easywatermark.session

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import me.rosuh.easywatermark.ui.IosProductRootHost
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G4 — iOS import / host memory lifecycle:
 * multi-item stage order + concurrency bound, host file-first (no sourceBytes pin),
 * cache budgets, trimCaches without Session wipe.
 */
class IosG4StagingMemoryTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
        IosStageConcurrencyProbe.reset()
        IosPickGenerationGate.resetForTests()
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
        IosStageConcurrencyProbe.reset()
    }

    private class IsolatedGraph(
        val services: IosAppServices,
        private val viewModelStore: ViewModelStore,
        private val stagedPaths: MutableList<String> = mutableListOf(),
    ) {
        fun trackAll(paths: List<String>) {
            stagedPaths += paths
        }

        fun close() {
            viewModelStore.clear()
            stagedPaths.forEach { IosSourceStager.deleteQuietly(it) }
            stagedPaths.clear()
        }
    }

    private fun isolatedGraph(): IsolatedGraph {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "g4_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "g4_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = null,
        )
        val store = ViewModelStore()
        store.put("g4-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return IsolatedGraph(services, store)
    }

    private fun solidPng(color: Color): ByteArray {
        val w = 48
        val h = 36
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
    fun multiItemStage_preservesOrder_andBoundsConcurrency() = runTest(mainDispatcher) {
        val graph = isolatedGraph()
        try {
            IosStageConcurrencyProbe.reset()
            val payloads = listOf(
                solidPng(Color(0xFFFF0000)),
                solidPng(Color(0xFF00FF00)),
                solidPng(Color(0xFF0000FF)),
                solidPng(Color(0xFFFFFF00)),
                solidPng(Color(0xFF00FFFF)),
                solidPng(Color(0xFFFF00FF)),
            )
            val gen = IosPickGenerationGate.nextPhotoGeneration()
            graph.services.stagePickedImagesBytes(
                imageBytesList = payloads,
                append = false,
                pickGeneration = gen,
            )
            val selected = graph.services.session.launchScreenUiStateFlow.first().selectedImageList
            assertEquals(6, selected.size)
            graph.trackAll(selected.map { it.uri.value })
            // Distinct staged paths in picker order (all ewm_src_*).
            val paths = selected.map { it.uri.value }
            assertTrue(paths.all { it.contains("ewm_src_") })
            assertEquals(paths.toSet().size, paths.size, "each item gets unique path")
            // Peak concurrent stage writers never exceeds G4 bound.
            assertTrue(
                IosStageConcurrencyProbe.peakInFlight() <= IOS_STAGING_MAX_CONCURRENCY,
                "peak=${IosStageConcurrencyProbe.peakInFlight()} > $IOS_STAGING_MAX_CONCURRENCY",
            )
            assertTrue(
                IosStageConcurrencyProbe.peakInFlight() >= 1,
                "probe should observe at least one stage enter",
            )
        } finally {
            graph.close()
        }
    }

    @Test
    fun hostCacheBudgets_evictFifo_andTrimClearsWithoutSessionWipe() = runTest(mainDispatcher) {
        val graph = isolatedGraph()
        try {
            val gen = IosPickGenerationGate.nextPhotoGeneration()
            val a = solidPng(Color.Red)
            val b = solidPng(Color.Blue)
            graph.services.stagePickedImagesBytes(
                imageBytesList = listOf(a, b),
                append = false,
                pickGeneration = gen,
            )
            val selected = graph.services.session.launchScreenUiStateFlow.first().selectedImageList
            assertEquals(2, selected.size)
            graph.trackAll(selected.map { it.uri.value })

            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            try {
                val tiny = ImageBitmap(2, 2, ImageBitmapConfig.Argb8888)
                // Fill past WM budget (8).
                val wmMax = IosProductRootHost.WM_PREVIEW_CACHE_MAX
                for (i in 0 until wmMax + 4) {
                    host.putWmPreviewForTests("wm_$i", tiny)
                }
                assertEquals(wmMax, host.cacheBudgetForTests().wmPreview)

                val placeMax = IosProductRootHost.PLACEHOLDER_CACHE_MAX
                for (i in 0 until placeMax + 3) {
                    host.putPlaceholderForTests("ph_$i", tiny)
                }
                assertEquals(placeMax, host.cacheBudgetForTests().placeholder)

                val filmMax = IosProductRootHost.FILMSTRIP_THUMB_CACHE_MAX
                for (i in 0 until filmMax + 2) {
                    host.putFilmstripThumbForTests("fs_$i", tiny)
                }
                assertEquals(filmMax, host.cacheBudgetForTests().filmstrip)

                // Session still holds selection before/after trim.
                assertEquals(
                    2,
                    graph.services.session.launchScreenUiStateFlow.first().selectedImageList.size,
                )
                host.trimCaches()
                val afterTrim = host.cacheBudgetForTests()
                assertEquals(0, afterTrim.wmPreview)
                assertEquals(0, afterTrim.placeholder)
                assertEquals(0, afterTrim.filmstrip)
                assertEquals(0, afterTrim.exportThumb)
                assertFalse(afterTrim.holdsSourceBytes)
                assertEquals(
                    2,
                    graph.services.session.launchScreenUiStateFlow.first().selectedImageList.size,
                    "trimCaches must not wipe Session selection",
                )
                assertFalse(host.isDisposedForTests())

                // onMemoryWarning is alias of trimCaches.
                host.putWmPreviewForTests("again", tiny)
                host.onMemoryWarning()
                assertEquals(0, host.cacheBudgetForTests().wmPreview)
            } finally {
                host.dispose()
            }
        } finally {
            graph.close()
        }
    }

    @Test
    fun deliverBatch_doesNotRetainSourceBytesOnHost() = runTest(mainDispatcher) {
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
                val gen = IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(solidPng(Color.Red), solidPng(Color.Green)),
                    append = false,
                    renderPreview = false,
                    pickGeneration = gen,
                )
                val selected = graph.services.session.launchScreenUiStateFlow.first().selectedImageList
                graph.trackAll(selected.map { it.uri.value })
                assertEquals(2, selected.size)
                assertFalse(
                    host.cacheBudgetForTests().holdsSourceBytes,
                    "G4 file-first: host must not pin multi full-res sourceBytes after stage",
                )
            } finally {
                host.dispose()
            }
        } finally {
            graph.close()
        }
    }
}
