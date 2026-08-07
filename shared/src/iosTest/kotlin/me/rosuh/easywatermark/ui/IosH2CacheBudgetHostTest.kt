@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.rosuh.easywatermark.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.IosUserConfigBridge
import me.rosuh.easywatermark.data.repo.IosWatermarkConfigBridge
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * H2: host put seams enforce byte budget (not entry-count alone).
 */
class IosH2CacheBudgetHostTest {

    private fun isolatedServices(): Pair<IosAppServices, ViewModelStore> {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "h2_budget_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "h2_budget_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = null,
        )
        val store = ViewModelStore()
        store.put("h2-budget-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        return services to store
    }

    @Test
    fun putWmPreview_overByteBudget_evictsOldest() = runBlocking {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            try {
                // ~16MB each; budget WM_PREVIEW_BYTES_MAX = 16MB → only one should remain.
                val huge = ImageBitmap(2000, 2000, ImageBitmapConfig.Argb8888)
                host.putWmPreviewForTests("old", huge)
                host.putWmPreviewForTests("new", huge)
                val snap = host.cacheBudgetForTests()
                assertTrue(snap.wmPreview <= 1, "byte budget must leave at most 1 huge entry; got ${snap.wmPreview}")
                assertTrue(
                    snap.wmPreviewBytes <= IosProductRootHost.WM_PREVIEW_BYTES_MAX,
                    "bytes ${snap.wmPreviewBytes} > ${IosProductRootHost.WM_PREVIEW_BYTES_MAX}",
                )
            } finally {
                host.dispose()
            }
        } finally {
            store.clear()
        }
        Unit
    }
}
