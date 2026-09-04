@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package me.rosuh.easywatermark.session

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
import me.rosuh.easywatermark.render.IosByteArrayInterop
import platform.Foundation.NSData
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue 26 / C4.4R.S1 — **production seam** identity tests for iOS staging:
 * [IosSourceStager] → [IosAppServices.stagePickedImagesBytes] → Session selection/focus → bytes-at-path.
 *
 * Hang root cause (fixed here): plain `runBlocking` on the native test thread blocked while
 * [WatermarkSessionViewModel.reduceAndPublish] awaited `Dispatchers.Main.immediate` with no Main
 * installed. Harness:
 * - [Dispatchers.setMain] with [UnconfinedTestDispatcher]
 * - [runTest] on the **same** [kotlinx.coroutines.test.TestCoroutineScheduler]
 * - [ViewModelStore.clear] so collector jobs do not outlive the test
 *
 * Do **not** wrap production suspend seams in `withTimeout` under virtual test time — that reports
 * false hangs when real IO runs off the virtual clock. Failures must be real assertion failures.
 *
 * Not a PHPicker/runtime gesture proof — that remains the separate R1/R2 Simulator gate.
 */
class IosSourceStagingIdentityTest {

    /**
     * Shared scheduler with [runTest] so Main.immediate and the test body advance together.
     * Created per-class and installed in [installMain]; not recreated per test so @Before/@After pair.
     */
    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private class IsolatedGraph(
        val services: IosAppServices,
        private val viewModelStore: ViewModelStore,
        private val stagedPaths: MutableList<String> = mutableListOf(),
    ) {
        fun track(path: String): String {
            stagedPaths += path
            return path
        }

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
            dataStore = createWaterMarkDataStore(name = "c44rs1_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "c44rs1_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = IosExportPipelinePort(),
        )
        // Register so [ViewModelStore.clear] cancels viewModelScope collectors.
        val store = ViewModelStore()
        store.put("c44rs1-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return IsolatedGraph(services, store)
    }

    private fun readBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("missing staged file: $path")
        return IosByteArrayInterop.fromNSData(data)
    }

    /**
     * K1 — production seam: fresh A then fresh B.
     * Session URI, selection list, focus, and bytes-at-path must all be B (not A).
     */
    @Test
    fun k1_fresh_b_replaces_a_session_uri_and_bytes_at_path() = runTest(mainDispatcher.scheduler) {
        val graph = isolatedGraph()
        try {
            val a = byteArrayOf(0x41, 0x41, 0x41, 0x0A)
            val b = byteArrayOf(0x42, 0x42, 0x42, 0x0A)

            me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
            val g1 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
            val pathA = graph.track(
                graph.services.stagePickedImagesBytes(listOf(a), append = false, pickGeneration = g1),
            )
            val launchA = graph.services.session.launchScreenUiStateFlow.first()
            assertEquals(pathA, launchA.curImageInfo?.uri?.value, "after A: Session focus URI is A")
            assertEquals(listOf(pathA), launchA.selectedImageList.map { it.uri.value })
            assertContentEquals(a, readBytes(pathA), "bytes-at-path A")

            val g2 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
            val pathB = graph.track(
                graph.services.stagePickedImagesBytes(listOf(b), append = false, pickGeneration = g2),
            )
            val launchB = graph.services.session.launchScreenUiStateFlow.first()
            assertNotEquals(pathA, pathB, "fresh B must mint a new staged path identity")
            assertEquals(
                listOf(pathB),
                launchB.selectedImageList.map { it.uri.value },
                "fresh B must replace Session selection (not append A)",
            )
            assertEquals(
                pathB,
                launchB.curImageInfo?.uri?.value,
                "fresh B must become Session curImageInfo URI",
            )
            assertContentEquals(b, readBytes(pathB), "bytes-at-path B must match staged B")
            assertTrue(
                launchB.selectedImageList.none { it.uri.value == pathA },
                "A must not remain selected after fresh B",
            )
        } finally {
            graph.close()
        }
    }

    /**
     * K2 — production seam: stage A fresh, append B.
     * Order A+B; focus remains A; both paths hold correct bytes.
     */
    @Test
    fun k2_append_preserves_focus_order_and_bytes() = runTest(mainDispatcher.scheduler) {
        val graph = isolatedGraph()
        try {
            val a = byteArrayOf(0x11, 0x22, 0x33)
            val b = byteArrayOf(0x44, 0x55, 0x66)

            me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
            val g1 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
            val pathA = graph.track(
                graph.services.stagePickedImagesBytes(listOf(a), append = false, pickGeneration = g1),
            )
            val g2 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
            val pathB = graph.track(
                graph.services.stagePickedImagesBytes(listOf(b), append = true, pickGeneration = g2),
            )
            val launch = graph.services.session.launchScreenUiStateFlow.first()

            assertEquals(
                listOf(pathA, pathB),
                launch.selectedImageList.map { it.uri.value },
                "append must preserve Session order A then B",
            )
            assertEquals(
                pathA,
                launch.curImageInfo?.uri?.value,
                "append must keep Session focus on A (add-more contract)",
            )
            assertContentEquals(a, readBytes(pathA), "bytes-at-path A")
            assertContentEquals(b, readBytes(pathB), "bytes-at-path B")
        } finally {
            graph.close()
        }
    }

    /**
     * K3 — production seam: one batch of two distinct payloads.
     * Distinct Session URIs; bytes-at-path match payload order.
     */
    @Test
    fun k3_batch_distinct_payloads_session_uris_and_bytes() = runTest(mainDispatcher.scheduler) {
        val graph = isolatedGraph()
        try {
            val left = ByteArray(32) { 0x0A }
            val right = ByteArray(32) { 0x0B }
            me.rosuh.easywatermark.session.IosPickGenerationGate.resetForTests()
            val g1 = me.rosuh.easywatermark.session.IosPickGenerationGate.nextPhotoGeneration()
            graph.services.stagePickedImagesBytes(listOf(left, right), append = false, pickGeneration = g1)
            val launch = graph.services.session.launchScreenUiStateFlow.first()
            val paths = launch.selectedImageList.map { it.uri.value }
            graph.trackAll(paths)

            assertEquals(2, paths.size)
            assertNotEquals(paths[0], paths[1], "Session URIs must be distinct")
            assertTrue(paths.all { it.contains("ewm_src_") }, "paths use ewm_src_ identity")
            assertContentEquals(left, readBytes(paths[0]), "bytes-at-path[0]")
            assertContentEquals(right, readBytes(paths[1]), "bytes-at-path[1]")
            assertEquals(
                paths[0],
                launch.curImageInfo?.uri?.value,
                "fresh multi-batch focuses first URI",
            )
        } finally {
            graph.close()
        }
    }
}
