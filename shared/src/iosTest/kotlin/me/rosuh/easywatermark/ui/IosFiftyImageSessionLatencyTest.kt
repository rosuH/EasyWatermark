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
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.UserPreferences
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.IosDecodePurposeProbe
import me.rosuh.easywatermark.render.IosImageIOOwnershipProbe
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import me.rosuh.easywatermark.session.ExportOutcome
import me.rosuh.easywatermark.session.ExportPipelinePort
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.IosPickGenerationGate
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.image.ProductThumbFetcher
import me.rosuh.easywatermark.ui.image.productThumbCacheKey
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * **N=50 image editor session** latency evidence on the shipped iOS host path (R1+R3).
 *
 * Post-picker spine: [IosProductRootHost.deliverPickedPhotosBatch] (no FilmstripRepo warm) →
 * sequential [switchImageAndAwaitForTests] with neighbor WM await → Coil [ProductThumbFetcher].
 *
 * Prints a single `FIFTY_IMAGE_SESSION` line for diagnosis reports / artifacts.
 */
class IosFiftyImageSessionLatencyTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val stagedPaths = mutableListOf<String>()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
        IosPickGenerationGate.resetForTests()
        IosDecodePurposeProbe.resetForTests()
        IosImageIOOwnershipProbe.resetForTests()
    }

    @AfterTest
    fun tearDown() {
        stagedPaths.distinct().forEach { IosSourceStager.deleteQuietly(it) }
        stagedPaths.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun fiftyImages_importSwitch_coilOnlyFilmstrip_andWmNeighborHits() =
        runTest(mainDispatcher.scheduler) {
            val n = 50
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
                    val images = List(n) { i ->
                        solidPng(
                            Color(
                                red = (40 + i * 3) % 200 / 255f,
                                green = (80 + i * 2) % 200 / 255f,
                                blue = (120 + i) % 200 / 255f,
                            ),
                        )
                    }

                    IosDecodePurposeProbe.resetForTests()
                    IosImageIOOwnershipProbe.resetForTests()

                    // --- Phase A: import (R3: no FilmstripRepo full-strip warm) ---
                    val gen = IosPickGenerationGate.nextPhotoGeneration()
                    val importMs = measureTime {
                        host.deliverPickedPhotosBatch(
                            images = images,
                            append = false,
                            renderPreview = true,
                            pickGeneration = gen,
                        )
                        // Focus WM may still be in hostScope — give it a beat under Unconfined.
                        delay(50)
                    }.inWholeMilliseconds
                    val afterImport = IosDecodePurposeProbe.snapshotForTests()
                    val launch = graph.services.session.launchScreenUiStateFlow.value
                    val paths = launch.selectedImageList.map { it.uri.value }
                    stagedPaths += paths
                    assertEquals(n, paths.size, "Session must hold $n staged paths")
                    assertEquals(
                        0,
                        afterImport.filmstripRepo,
                        "R3: import must not ImageIO-decode FilmstripRepo " +
                            "(filmstripRepo=${afterImport.filmstripRepo})",
                    )
                    val recompose = host.recomposeWatermarkFromCachedSourceForTests()
                    assertEquals(
                        "source_reuse",
                        recompose,
                        "same path + same preview long-edge must recompose without ImageIO",
                    )

                    // --- Phase B: sequential switch across all 50 (product switch + neighbor await) ---
                    val switchMs = LongArray(n)
                    val switchHits = Array(n) { "" }
                    for (i in paths.indices) {
                        val t = host.switchImageAndAwaitForTests(paths[i])
                        switchMs[i] = t.totalMs
                        switchHits[i] = t.hit
                    }
                    val missMs = switchMs.filterIndexed { i, _ -> switchHits[i] == "miss" }
                    val hitMs = switchMs.filterIndexed { i, _ -> switchHits[i] != "miss" }
                    val afterSwitch = IosDecodePurposeProbe.snapshotForTests()
                    val wmDuringSwitch =
                        afterSwitch.watermarkedPreview - afterImport.watermarkedPreview

                    // R1: neighbor ±2 warm + larger WM cache → miss rate ≪ 49/50.
                    // Sequential 0..49 with ±2 warm: first is miss, each later often hits
                    // prior neighbor window. Allow some misses for cold starts / gen races.
                    assertTrue(
                        missMs.size <= 20,
                        "R1 expected miss_n ≤ 20 at N=50 sequential (was 49); " +
                            "misses=${missMs.size} hits=${hitMs.size} hits=${switchHits.toList()}",
                    )
                    assertTrue(
                        hitMs.size >= 30,
                        "R1 expected majority cache hits (hits=${hitMs.size})",
                    )
                    // Source reuse: renderWatermarked must not ImageIO on switch when
                    // Host injects the cached source. Misses can now be zero.
                    assertEquals(
                        0,
                        wmDuringSwitch,
                        "switch must compose from injected source " +
                            "(wmDelta=$wmDuringSwitch misses=${missMs.size})",
                    )
                    assertEquals(
                        0,
                        afterSwitch.filmstripRepo,
                        "R3: switch must not touch FilmstripRepo",
                    )

                    // --- Phase C: Coil ProductThumb fetch for same 50 paths (filmstrip UI) ---
                    val coilStart = IosDecodePurposeProbe.snapshotForTests()
                    val loader = ImageLoader.Builder(coil3.PlatformContext.INSTANCE)
                        .components { add(ProductThumbFetcher.Factory()) }
                        .build()
                    val coilMs = LongArray(n)
                    for (i in paths.indices) {
                        val thumb = ProductThumb(
                            ref = MediaRef(paths[i]),
                            maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
                        )
                        val key = productThumbCacheKey(thumb.ref.value, thumb.maxEdgePx)
                        val req = ImageRequest.Builder(coil3.PlatformContext.INSTANCE)
                            .data(thumb)
                            .memoryCacheKey(key)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .size(thumb.maxEdgePx)
                            .precision(Precision.INEXACT)
                            .build()
                        coilMs[i] = measureTime {
                            val result = loader.execute(req)
                            assertTrue(result is SuccessResult, "Coil thumb $i must succeed")
                        }.inWholeMilliseconds
                    }
                    val afterCoil = IosDecodePurposeProbe.snapshotForTests()
                    val coilDecodes =
                        afterCoil.productThumbCoil - coilStart.productThumbCoil

                    assertEquals(
                        0,
                        afterCoil.filmstripRepo,
                        "R3: FilmstripRepo stays 0 after Coil phase (${afterCoil.filmstripRepo})",
                    )
                    assertTrue(
                        coilDecodes >= n - 2,
                        "ProductThumbCoil must decode nearly all $n paths (got $coilDecodes)",
                    )

                    val missSorted = missMs.sorted()
                    val missMed = if (missSorted.isNotEmpty()) {
                        missSorted[missSorted.size / 2]
                    } else {
                        -1L
                    }
                    val missP90 = if (missSorted.isNotEmpty()) {
                        missSorted[(missSorted.size * 9 / 10).coerceAtMost(missSorted.lastIndex)]
                    } else {
                        -1L
                    }
                    val coilSorted = coilMs.sorted()
                    val coilMed = coilSorted[coilSorted.size / 2]
                    val coilSum = coilMs.sum()
                    val switchSum = switchMs.sum()
                    val switchMax = switchMs.max()

                    println(
                        "FIFTY_IMAGE_SESSION n=$n " +
                            "import_ms=$importMs " +
                            "switch_sum_ms=$switchSum switch_max_ms=$switchMax " +
                            "switch_miss_n=${missMs.size} switch_miss_med_ms=$missMed " +
                            "switch_miss_p90_ms=$missP90 " +
                            "switch_hit_n=${hitMs.size} " +
                            "coil_thumb_sum_ms=$coilSum coil_thumb_med_ms=$coilMed " +
                            "decode_filmstrip_repo=${afterCoil.filmstripRepo} " +
                            "decode_product_thumb_coil=${afterCoil.productThumbCoil} " +
                            "decode_wm_preview=${afterCoil.watermarkedPreview} " +
                            "decode_placeholder=${afterCoil.sourcePlaceholder} " +
                            "wm_on_main=${afterCoil.watermarkedOnMain} " +
                            "wm_cache_entries=${host.cacheBudgetForTests().wmPreview}",
                    )
                    assertEquals(
                        0,
                        afterCoil.watermarkedOnMain,
                        "WM raster (focus + neighbor prefetch) must not run on Main " +
                            "(onMain=${afterCoil.watermarkedOnMain} wm=${afterCoil.watermarkedPreview})",
                    )

                    if (missMs.isNotEmpty()) {
                        assertTrue(
                            missMed >= 0L,
                            "cold switch miss med must be measurable when misses exist (got $missMed)",
                        )
                    }
                    assertTrue(
                        coilMed >= 0L && coilSum >= 1L,
                        "Coil filmstrip loads must take wall time (sum=$coilSum med=$coilMed)",
                    )
                } finally {
                    host.dispose()
                }
            } finally {
                graph.close()
            }
        }

    private class Graph(
        val services: IosAppServices,
        private val viewModelStore: ViewModelStore,
        private val stagedPaths: MutableList<String>,
    ) {
        fun close() {
            viewModelStore.clear()
            val fromSession = services.session.launchScreenUiStateFlow.value
                .selectedImageList.map { it.uri.value }
            (stagedPaths + fromSession).distinct().forEach { IosSourceStager.deleteQuietly(it) }
            stagedPaths.clear()
        }
    }

    private fun isolatedGraph(): Graph {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "fifty_lat_wm_$id"),
            defaultTextProvider = { "EasyWatermark" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "fifty_lat_uc_$id"),
        )
        val exportPort = object : ExportPipelinePort {
            override suspend fun exportOne(
                imageInfo: me.rosuh.easywatermark.data.model.ImageInfo,
                config: WaterMark,
                prefs: UserPreferences,
            ): ExportOutcome = error("export not used in latency session")
        }
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = exportPort,
        )
        val store = ViewModelStore()
        store.put("fifty-lat-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return Graph(services, store, stagedPaths)
    }

    private fun solidPng(color: Color): ByteArray {
        val w = 800
        val h = 600
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return IosWatermarkRenderer.encodePng(bmp)
    }
}
