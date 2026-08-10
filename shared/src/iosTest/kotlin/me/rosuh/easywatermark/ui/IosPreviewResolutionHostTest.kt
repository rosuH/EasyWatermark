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
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.render.IosByteArrayInterop
import me.rosuh.easywatermark.render.IosPreviewRaster
import me.rosuh.easywatermark.render.IosWatermarkRenderer
import me.rosuh.easywatermark.render.PreviewResolutionPolicy
import me.rosuh.easywatermark.session.AppIntent
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host production seam for adaptive preview buckets (attempt 2):
 * single [IosProductRootHost.applyPreviewBoxSizeForTests] path, real cache clear, dispose fail-closed.
 */
class IosPreviewResolutionHostTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun installMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun isolatedServices(): Pair<IosAppServices, ViewModelStore> {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "adapt_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "adapt_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = null,
        )
        val store = ViewModelStore()
        store.put("adapt-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return services to store
    }

    private fun solid(w: Int, h: Int, color: Color): ImageBitmap {
        val bmp = ImageBitmap(w, h, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bmp),
            size = Size(w.toFloat(), h.toFloat()),
        ) { drawRect(color) }
        return bmp
    }

    @Test
    fun previewBoxSize_cacheLifecycle_andDisposeFailClosed() = runTest {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            val path = "/tmp/ewm_src_adapt_${NSUUID().UUIDString()}.png"
            assertEquals(720, host.committedPreviewMaxEdgePxForTests())
            val gen0 = host.previewGenForTests()

            // Insert a committed cache entry at the default 720 bucket.
            val bmp720 = solid(720, 540, Color(0xFF203040))
            host.putWmPreviewForTests(path, bmp720)
            assertTrue(path in host.previewIdentityForTests().wmCachePaths)

            // Same-bucket remeasure: no-op (cache retained, gen unchanged).
            host.applyPreviewBoxSizeForTests(640, 400)
            assertEquals(720, host.committedPreviewMaxEdgePxForTests())
            assertEquals(gen0, host.previewGenForTests())
            assertTrue(path in host.previewIdentityForTests().wmCachePaths)

            // Invalid size: no-op.
            host.applyPreviewBoxSizeForTests(0, 800)
            host.applyPreviewBoxSizeForTests(-1, 1206)
            assertEquals(720, host.committedPreviewMaxEdgePxForTests())
            assertEquals(gen0, host.previewGenForTests())
            assertTrue(path in host.previewIdentityForTests().wmCachePaths)

            // Bucket change 720 → 1440: clear cache + one gen bump.
            host.applyPreviewBoxSizeForTests(1206, 800)
            assertEquals(1440, host.committedPreviewMaxEdgePxForTests())
            assertTrue(host.previewGenForTests() > gen0)
            assertFalse(
                path in host.previewIdentityForTests().wmCachePaths,
                "bucket transition must clear committed wm cache",
            )
            val genAfter1440 = host.previewGenForTests()

            // Same-bucket resize: no further gen bump.
            host.applyPreviewBoxSizeForTests(1300, 900)
            assertEquals(1440, host.committedPreviewMaxEdgePxForTests())
            assertEquals(genAfter1440, host.previewGenForTests())

            // Naturally small source is still a valid *current-bucket* cache hit (no dim heuristic).
            val small = solid(100, 80, Color.Red)
            host.putWmPreviewForTests(path, small)
            assertTrue(path in host.previewIdentityForTests().wmCachePaths)
            host.applyPreviewBoxSizeForTests(1100, 700) // still 1440 bucket
            assertTrue(
                path in host.previewIdentityForTests().wmCachePaths,
                "same-bucket must retain naturally small cache entry",
            )

            // Transition away still invalidates.
            host.applyPreviewBoxSizeForTests(2000, 1500) // 1920
            assertEquals(1920, host.committedPreviewMaxEdgePxForTests())
            assertFalse(path in host.previewIdentityForTests().wmCachePaths)

            // Dispose fail-closed: size updates ignored.
            host.dispose()
            assertTrue(host.isDisposedForTests())
            val genDisposed = host.previewGenForTests()
            val bucketDisposed = host.committedPreviewMaxEdgePxForTests()
            host.applyPreviewBoxSizeForTests(1206, 800)
            assertEquals(bucketDisposed, host.committedPreviewMaxEdgePxForTests())
            assertEquals(genDisposed, host.previewGenForTests())
        } finally {
            store.clear()
        }
    }

    @Test
    fun staleGeneration_afterBucketChange_doesNotRepopulateCache() = runTest {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            // Real PNG + Image-mode (avoids bundled font lookup in native tests).
            val dir = NSTemporaryDirectory()
            val sourcePath = dir + "adapt_src_" + NSUUID().UUIDString() + ".png"
            val iconPath = dir + "adapt_icon_" + NSUUID().UUIDString() + ".png"
            val png = IosWatermarkRenderer.encodePng(solid(400, 300, Color(0xFF405060)))
            val iconPng = IosWatermarkRenderer.encodePng(solid(32, 24, Color.Red))
            assertTrue(IosByteArrayInterop.toNSData(png).writeToFile(sourcePath, atomically = true))
            assertTrue(IosByteArrayInterop.toNSData(iconPng).writeToFile(iconPath, atomically = true))

            services.session.dispatchAndAwait(
                AppIntent.EnterEditor(
                    selected = listOf(ImageInfo(uri = MediaRef(sourcePath))),
                    waterMark = WaterMark.default,
                ),
            )
            // Persist Image mode + icon into the repo the host reads for paint.
            services.session.dispatchAndAwait(
                AppIntent.ApplyConfig(WatermarkConfigChange.Icon(MediaRef(iconPath))),
            )

            // Commit at 720, then transition to 1440 (clears cache, bumps gen).
            host.applyPreviewBoxSizeForTests(600, 400)
            val genBefore = host.previewGenForTests()
            host.renderPreviewForCurrentSelectionForTests(gen = genBefore)
            assertTrue(sourcePath in host.previewIdentityForTests().wmCachePaths)

            host.applyPreviewBoxSizeForTests(1206, 800)
            val genAfter = host.previewGenForTests()
            assertTrue(genAfter > genBefore)
            assertFalse(sourcePath in host.previewIdentityForTests().wmCachePaths)

            // In-flight work carrying the *old* gen must not repopulate cache / display.
            host.renderPreviewForCurrentSelectionForTests(gen = genBefore)
            assertFalse(
                sourcePath in host.previewIdentityForTests().wmCachePaths,
                "stale gen must not cache after bucket transition",
            )
            // Fresh gen may repopulate under the new bucket.
            host.renderPreviewForCurrentSelectionForTests(gen = genAfter)
            assertTrue(sourcePath in host.previewIdentityForTests().wmCachePaths)
            assertEquals(sourcePath, host.previewIdentityForTests().previewSourcePath)

            host.dispose()
        } finally {
            store.clear()
        }
    }
}
