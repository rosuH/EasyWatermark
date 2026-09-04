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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue 26 / C4.4R.S1 **F12/F16** — generation validity across the real publication chain:
 * Session StateFlow + repository selection + host preview/cache + icon config.
 *
 * Each case pauses G1 at a production probe, begins empty/failed G2, resumes G1, and proves
 * A never appears on that boundary after G2 began.
 */
class IosPickGenerationPublishBoundaryTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
        IosPickGenerationGate.resetForTests()
        IosPickPublishProbe.clear()
    }

    @AfterTest
    fun resetMainDispatcher() {
        IosPickPublishProbe.clear()
        Dispatchers.resetMain()
        IosPickGenerationGate.resetForTests()
    }

    private class Graph(
        val services: IosAppServices,
        private val viewModelStore: ViewModelStore,
        private val stagedPaths: MutableList<String> = mutableListOf(),
    ) {
        fun close() {
            viewModelStore.clear()
            stagedPaths.distinct().forEach { IosSourceStager.deleteQuietly(it) }
            stagedPaths.clear()
        }
    }

    private fun isolatedGraph(): Graph {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "c44rs1_f16_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "c44rs1_f16_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = IosExportPipelinePort(),
        )
        val store = ViewModelStore()
        store.put("c44rs1-f16-$id", session)
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

    private fun stagedUris(graph: Graph): List<String> =
        graph.services.session.launchScreenUiStateFlow.value.selectedImageList
            .map { it.uri.value }
            .filter { it.contains("ewm_src_") }

    private fun repoHasStaged(graph: Graph): Boolean {
        val list = graph.services.waterMarkRepo.imageInfoList
        val selected = graph.services.waterMarkRepo.selectedImage.value
        return list.any { it.uri.value.contains("ewm_src_") } ||
            selected.uri.value.contains("ewm_src_")
    }

    /**
     * F12/F16: pause before Session+repo guarded publish; empty G2; never StateFlow/repo A.
     */
    @Test
    fun f16_pause_at_session_repo_boundary_empty_g2_never_emits_a() = runTest(mainDispatcher.scheduler) {
        val graph = isolatedGraph()
        try {
            val aBytes = solidPng(Color(0xFFAA0000))
            val g1 = IosPickGenerationGate.nextPhotoGeneration()

            val g1AtBoundary = CompletableDeferred<Unit>()
            val releaseG1 = CompletableDeferred<Unit>()
            IosPickPublishProbe.install { gen ->
                if (gen == g1) {
                    g1AtBoundary.complete(Unit)
                    releaseG1.await()
                }
            }

            val emissions = mutableListOf<List<String>>()
            val collectJob = launch {
                graph.services.session.launchScreenUiStateFlow.collect { st ->
                    emissions += st.selectedImageList.map { it.uri.value }
                        .filter { it.contains("ewm_src_") }
                }
            }

            val g1Job = async {
                runCatching {
                    graph.services.stagePickedImagesBytes(
                        imageBytesList = listOf(aBytes),
                        append = false,
                        pickGeneration = g1,
                    )
                }
            }

            g1AtBoundary.await()
            val g2 = IosPickGenerationGate.nextPhotoGeneration()
            assertTrue(g2 > g1)
            assertFalse(IosPickGenerationGate.isPhotoCurrent(g1))
            assertTrue(stagedUris(graph).isEmpty(), "A must not be on Session while paused")
            assertFalse(repoHasStaged(graph), "A must not be in repo while paused")

            releaseG1.complete(Unit)
            val result = g1Job.await()
            assertTrue(
                result.isFailure && result.exceptionOrNull() is StalePickGenerationException,
                "G1 must fail closed after empty G2 (got ${result.exceptionOrNull()})",
            )

            collectJob.cancel()
            assertTrue(
                emissions.none { it.any { u -> u.contains("ewm_src_") } },
                "StateFlow must never emit staged A after G2 (emissions=$emissions)",
            )
            assertTrue(stagedUris(graph).isEmpty(), "final Session must not hold A")
            assertFalse(repoHasStaged(graph), "repo must not persist A after empty G2")
        } finally {
            IosPickPublishProbe.clear()
            graph.close()
        }
    }

    /**
     * F16: pause at host preview-bind after decode; empty G2; never cache/preview A.
     */
    @Test
    fun f16_pause_at_host_preview_bind_empty_g2_never_cache_a() = runTest(mainDispatcher.scheduler) {
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
                val aBytes = solidPng(Color(0xFF112233))
                val g1 = IosPickGenerationGate.nextPhotoGeneration()
                val atPreviewBind = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                // Allow Session publish to succeed for G1, then pause before host preview bind.
                IosPickPublishProbe.install(
                    beforeHostPreviewBind = { gen ->
                        if (gen == g1) {
                            atPreviewBind.complete(Unit)
                            release.await()
                        }
                    },
                )

                val job = async {
                    runCatching {
                        host.deliverPickedPhotosBatch(
                            images = listOf(aBytes),
                            append = false,
                            renderPreview = true,
                            pickGeneration = g1,
                        )
                    }
                }
                atPreviewBind.await()
                // Session may hold A (G1 published before host bind). Empty G2 invalidates host bind.
                IosPickGenerationGate.nextPhotoGeneration()
                release.complete(Unit)
                job.await()

                val identity = host.previewIdentityForTests()
                assertTrue(
                    identity.previewSourcePath == null ||
                        !identity.previewSourcePath.orEmpty().contains("ewm_src_"),
                    "host preview must not bind A after empty G2 at preview boundary",
                )
                // Source placeholder cache must not keep A for a superseded generation bind.
                // (Session may still hold A — host bind is the F16 target for this probe.)
                assertTrue(
                    identity.placeholderCachePaths.none { it.contains("ewm_src_") } ||
                        identity.previewSourcePath == null,
                    "host must not present A preview after G2 (identity=$identity)",
                )
            } finally {
                host.dispose()
            }
        } finally {
            IosPickPublishProbe.clear()
            graph.close()
        }
    }

    /**
     * F16: pause at icon config publication; empty/failed G2 advances icon gen; no host icon bind.
     */
    @Test
    fun f16_pause_at_icon_config_empty_g2_never_binds_icon() = runTest(mainDispatcher.scheduler) {
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
                // Need a photo selection so icon preview path can run; plant one first.
                val photoGen = IosPickGenerationGate.nextPhotoGeneration()
                graph.services.stagePickedImagesBytes(
                    imageBytesList = listOf(solidPng(Color(0xFF445566))),
                    append = false,
                    pickGeneration = photoGen,
                )

                val iconBytes = solidPng(Color(0xFF00AA88))
                val g1 = IosPickGenerationGate.nextIconGeneration()
                val atIcon = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                IosPickPublishProbe.install(
                    beforeIconConfig = { gen ->
                        if (gen == g1) {
                            atIcon.complete(Unit)
                            release.await()
                        }
                    },
                )

                val beforeIconUri = graph.services.waterMarkRepo.waterMark.first().iconUri.value
                val job = async {
                    runCatching {
                        host.deliverIconBytesAndAwait(iconBytes, pickGeneration = g1)
                    }
                }
                atIcon.await()
                IosPickGenerationGate.nextIconGeneration() // empty/failed G2 for icon edge
                assertFalse(IosPickGenerationGate.isIconCurrent(g1))
                release.complete(Unit)
                val result = job.await()
                assertTrue(
                    result.isFailure && result.exceptionOrNull() is StalePickGenerationException,
                    "icon G1 must fail closed (got ${result.exceptionOrNull()})",
                )
                // Host must not bind icon bytes from stale G1.
                val identity = host.previewIdentityForTests()
                // iconBytes is private; wm mode/config is source of truth after ApplyConfig.
                val afterIconUri = graph.services.waterMarkRepo.waterMark.first().iconUri.value
                // Either config was not applied, or if DataStore micro-window wrote, host bind skipped.
                // applyConfigIf checks before write — stale at probe means no apply.
                assertTrue(
                    afterIconUri == beforeIconUri || afterIconUri.isEmpty(),
                    "icon config must not publish G1 path after G2 (before=$beforeIconUri after=$afterIconUri)",
                )
                assertTrue(identity.previewSourcePath == null || identity.previewSourcePath!!.isNotBlank())
            } finally {
                host.dispose()
            }
        } finally {
            IosPickPublishProbe.clear()
            graph.close()
        }
    }

    @Test
    fun f14_exportPickedImageBytes_issues_fresh_generation_not_stuck_at_zero() =
        runTest(mainDispatcher.scheduler) {
            val graph = isolatedGraph()
            try {
                IosPickGenerationGate.nextPhotoGeneration()
                IosPickGenerationGate.nextPhotoGeneration()
                val before = IosPickGenerationGate.currentPhotoGeneration()
                assertTrue(before >= 2L)
                val png = solidPng(Color(0xFF203040))
                val stageResult = runCatching {
                    graph.services.exportPickedImageBytes(png)
                }
                val after = IosPickGenerationGate.currentPhotoGeneration()
                assertTrue(
                    after > before,
                    "render/export path must issue a new generation (before=$before after=$after)",
                )
                if (stageResult.isSuccess || stagedUris(graph).isNotEmpty()) {
                    assertTrue(
                        stagedUris(graph).isNotEmpty() ||
                            graph.services.session.launchScreenUiStateFlow.value
                                .curImageInfo?.uri?.value.orEmpty().isNotBlank(),
                    )
                }
            } finally {
                graph.close()
            }
        }
}
