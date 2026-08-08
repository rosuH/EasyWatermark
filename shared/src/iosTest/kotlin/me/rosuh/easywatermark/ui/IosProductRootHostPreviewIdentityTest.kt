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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
}
