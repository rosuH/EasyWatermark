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
import me.rosuh.easywatermark.render.IosPhotoKitNeighborCache
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import me.rosuh.easywatermark.session.ExportOutcome
import me.rosuh.easywatermark.session.ExportPipelinePort
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.IosAssetIdentityRegistry
import me.rosuh.easywatermark.session.IosPhotoLibraryAccess
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-0029 P4 — daemon-side focus±2 PhotoKit cache pairing.
 * Fake only; no PHAsset pixels and no repository puts.
 */
class IosPhotoKitNeighborCacheTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private class RecordingCache : IosPhotoKitNeighborCache {
        val starts = mutableListOf<Pair<Set<String>, Int>>()
        val stops = mutableListOf<Pair<Set<String>, Int>>()
        var stopAllCount = 0
        val cached = linkedSetOf<String>()

        override fun start(assetIds: Collection<String>, targetPx: Int) {
            starts += assetIds.toSet() to targetPx
            cached += assetIds
        }

        override fun stop(assetIds: Collection<String>, targetPx: Int) {
            stops += assetIds.toSet() to targetPx
            cached.removeAll(assetIds.toSet())
        }

        override fun stopAll() {
            stopAllCount += 1
            cached.clear()
        }
    }

    private class NoopExport : ExportPipelinePort {
        override suspend fun exportOne(
            imageInfo: me.rosuh.easywatermark.data.model.ImageInfo,
            config: me.rosuh.easywatermark.data.model.WaterMark,
            prefs: me.rosuh.easywatermark.data.model.UserPreferences,
        ): ExportOutcome = ExportOutcome.success(
            me.rosuh.easywatermark.data.model.ExportedMedia(
                ref = me.rosuh.easywatermark.data.model.MediaRef("file://p4-unused"),
                width = 1,
                height = 1,
                format = me.rosuh.easywatermark.data.model.ImageFormat.JPEG,
                byteCount = 1L,
            ),
        )
    }

    private class Graph(
        val services: IosAppServices,
        private val store: ViewModelStore,
        private val staged: MutableList<String> = mutableListOf(),
    ) {
        fun track() {
            staged += services.session.launchScreenUiStateFlow.value
                .selectedImageList.map { it.uri.value }
        }

        fun close() {
            store.clear()
            staged.distinct().forEach { IosSourceStager.deleteQuietly(it) }
            staged.clear()
        }
    }

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
        IosPhotoLibraryAccess.resetForTests()
        IosAssetIdentityRegistry.resetForTests()
    }

    @AfterTest
    fun reset() {
        Dispatchers.resetMain()
        IosPhotoLibraryAccess.resetForTests()
        IosAssetIdentityRegistry.resetForTests()
    }

    private fun isolatedGraph(): Graph {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "p4_nb_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "p4_nb_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = NoopExport(),
        )
        val store = ViewModelStore()
        store.put("p4-nb-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return Graph(services, store)
    }

    private fun solidPng(color: Color): ByteArray {
        val bmp = ImageBitmap(32, 24, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(32f, 24f),
        ) { drawRect(color) }
        return IosWatermarkRenderer.encodePng(bmp)
    }

    @Test
    fun focus_plus_minus_2_starts_neighbors_then_moves_and_dispose_stops_all() =
        runTest(mainDispatcher) {
            val graph = isolatedGraph()
            val cache = RecordingCache()
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = graph.services,
            )
            try {
                IosPhotoLibraryAccess.installStatusForTests(
                    IosPhotoLibraryAccess.Status.Authorized,
                )
                host.installNeighborCacheForTests(cache)
                host.installPhotoKitFastPathForTests { _, _ -> null }
                val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
                host.deliverPickedPhotosBatch(
                    images = listOf(
                        solidPng(Color.Red),
                        solidPng(Color.Green),
                        solidPng(Color.Blue),
                        solidPng(Color.Yellow),
                        solidPng(Color.Cyan),
                    ),
                    append = false,
                    renderPreview = false,
                    pickGeneration = gen,
                )
                graph.track()
                val paths = graph.services.session.launchScreenUiStateFlow.value
                    .selectedImageList.map { it.uri.value }
                assertEquals(5, paths.size)
                paths.forEachIndexed { i, path ->
                    IosAssetIdentityRegistry.put(path, "p4-id-$i")
                }
                host.switchImageAndAwaitForTests(paths[2], awaitNeighbors = true)
                assertEquals(
                    setOf("p4-id-0", "p4-id-1", "p4-id-3", "p4-id-4"),
                    cache.cached,
                    "focus index 2 must cache ±2 only (not the focus id)",
                )
                assertTrue(cache.starts.isNotEmpty())
                assertEquals(720, cache.starts.last().second)
                assertFalse("p4-id-2" in cache.cached)

                host.switchImageAndAwaitForTests(paths[3], awaitNeighbors = true)
                assertEquals(
                    setOf("p4-id-1", "p4-id-2", "p4-id-4"),
                    cache.cached,
                    "focus index 3 window is 1,2,4",
                )
                assertTrue(cache.stops.any { "p4-id-0" in it.first })
                assertTrue(cache.starts.any { "p4-id-2" in it.first })

                val identity = host.previewIdentityForTests()
                assertFalse(
                    identity.wmCachePaths.any { it.startsWith("p4-id-") },
                    "PhotoKit asset ids must not be wm cache keys",
                )

                val stopAllBefore = cache.stopAllCount
                host.dispose()
                assertEquals(stopAllBefore + 1, cache.stopAllCount)
                assertTrue(cache.cached.isEmpty())
                host.dispose()
                assertEquals(stopAllBefore + 1, cache.stopAllCount)
            } finally {
                host.installNeighborCacheForTests(null)
                if (!host.isDisposedForTests()) host.dispose()
                graph.close()
            }
        }

    @Test
    fun denied_does_not_start_cache() = runTest(mainDispatcher) {
        val graph = isolatedGraph()
        val cache = RecordingCache()
        val host = IosProductRootHost(
            onPickPhoto = {},
            onPickIcon = {},
            onShare = {},
            onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
            services = graph.services,
        )
        try {
            IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
            host.installNeighborCacheForTests(cache)
            host.installPhotoKitFastPathForTests { _, _ -> null }
            val gen = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
            host.deliverPickedPhotosBatch(
                images = listOf(solidPng(Color.Gray), solidPng(Color.Black)),
                append = false,
                renderPreview = false,
                pickGeneration = gen,
            )
            graph.track()
            val paths = graph.services.session.launchScreenUiStateFlow.value
                .selectedImageList.map { it.uri.value }
            paths.forEachIndexed { i, path ->
                IosAssetIdentityRegistry.put(path, "p4-denied-$i")
            }
            host.switchImageAndAwaitForTests(paths.first(), awaitNeighbors = true)
            assertTrue(cache.starts.isEmpty(), "Denied must not start PhotoKit neighbor cache")
            assertTrue(cache.cached.isEmpty())
        } finally {
            host.installNeighborCacheForTests(null)
            host.dispose()
            graph.close()
        }
    }
}
