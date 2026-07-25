package me.rosuh.easywatermark.ui

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
import me.rosuh.easywatermark.session.IosSourceStager
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2 L2 — iOS host dispose: clear caches, remove owned temps, idempotent.
 *
 * Avoids full picker deliver (heavy / Main-dispatcher sensitive); plants owned
 * `ewm_src_*` paths via the test seam and proves dispose cleanup.
 */
class IosProductRootDisposeTest {

    private fun isolatedServices(): Pair<IosAppServices, ViewModelStore> {
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "e2_dispose_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "e2_dispose_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = null,
        )
        val store = ViewModelStore()
        store.put("e2-dispose-session-$id", session)
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
    fun dispose_clearsCaches_deletesOwnedTemps_isIdempotent() = runBlocking {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )

            // Plant a real app-owned staged file and register it on the host.
            val staged = IosSourceStager.stageBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            assertTrue(staged.contains("ewm_src_"))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(staged))
            host.trackOwnedStagedPathForTests(staged)
            assertEquals(setOf(staged), host.ownedStagedPathsForTests())

            host.dispose()
            assertTrue(host.isDisposedForTests())
            val identity = host.previewIdentityForTests()
            assertEquals(null, identity.previewSourcePath)
            assertTrue(identity.wmCachePaths.isEmpty())
            assertTrue(identity.placeholderCachePaths.isEmpty())
            assertTrue(host.ownedStagedPathsForTests().isEmpty())
            assertFalse(
                NSFileManager.defaultManager.fileExistsAtPath(staged),
                "dispose must remove owned ewm_src temp",
            )

            // Idempotent second dispose — no throw, still disposed.
            host.dispose()
            assertTrue(host.isDisposedForTests())
            assertTrue(host.ownedStagedPathsForTests().isEmpty())
        } finally {
            store.clear()
        }
        Unit
    }

    @Test
    fun dispose_cancelExport_isSafeWhenIdle() = runBlocking {
        val (services, store) = isolatedServices()
        try {
            val host = IosProductRootHost(
                onPickPhoto = {},
                onPickIcon = {},
                onShare = {},
                onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
                services = services,
            )
            // No export running — cancel + dispose must still complete.
            services.session.cancelExport()
            host.dispose()
            assertTrue(host.isDisposedForTests())
            host.dispose()
        } finally {
            store.clear()
        }
        Unit
    }
}
