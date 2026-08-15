@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import me.rosuh.easywatermark.session.ExportOutcome
import me.rosuh.easywatermark.session.ExportPipelinePort
import me.rosuh.easywatermark.session.IosAppServices
import me.rosuh.easywatermark.session.IosPhotoLibraryAccess
import me.rosuh.easywatermark.session.WatermarkSessionViewModel
import platform.Foundation.NSUUID
import platform.UIKit.UIApplicationOpenSettingsURLString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-0029 P5 — pick-time dialog only. No chrome strip on Launch or Editor.
 */
class IosLibraryReadUpsellTest {

    private class NoopExportPort : ExportPipelinePort {
        override suspend fun exportOne(
            imageInfo: me.rosuh.easywatermark.data.model.ImageInfo,
            config: me.rosuh.easywatermark.data.model.WaterMark,
            prefs: me.rosuh.easywatermark.data.model.UserPreferences,
        ): ExportOutcome = ExportOutcome.success(
            me.rosuh.easywatermark.data.model.ExportedMedia(
                ref = me.rosuh.easywatermark.data.model.MediaRef("file://p5-unused"),
                width = 1,
                height = 1,
                format = me.rosuh.easywatermark.data.model.ImageFormat.JPEG,
                byteCount = 1L,
            ),
        )
    }

    private lateinit var store: ViewModelStore
    private lateinit var host: IosProductRootHost
    private val openedUrls = mutableListOf<String>()
    private var pickCalls = 0

    @BeforeTest
    fun setUp() {
        IosPhotoLibraryAccess.resetForTests()
        val id = NSUUID().UUIDString()
        val waterMarkRepo = WaterMarkRepository(
            dataStore = createWaterMarkDataStore(name = "p5_upsell_wm_$id"),
            defaultTextProvider = { "EasyWatermark 水印" },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = {},
        )
        val userConfigRepo = UserConfigRepository(
            createUserConfigDataStore(name = "p5_upsell_uc_$id"),
        )
        val session = WatermarkSessionViewModel(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            exportPipeline = NoopExportPort(),
        )
        store = ViewModelStore()
        store.put("p5-upsell-session-$id", session)
        val services = IosAppServices(
            waterMarkRepo = waterMarkRepo,
            userConfigRepo = userConfigRepo,
            configBridge = IosWatermarkConfigBridge(waterMarkRepo),
            userConfigBridge = IosUserConfigBridge(userConfigRepo),
            session = session,
        )
        openedUrls.clear()
        pickCalls = 0
        host = IosProductRootHost(
            onPickPhoto = { pickCalls += 1 },
            onPickIcon = {},
            onShare = {},
            onSaveToPhotos = { _, onComplete -> onComplete(true, null) },
            onOpenUrl = { openedUrls += it },
            services = services,
        )
    }

    @AfterTest
    fun tearDown() {
        host.dispose()
        store.clear()
        IosPhotoLibraryAccess.resetForTests()
    }

    @Test
    fun launch_idle_has_no_strip() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.refreshLibraryReadBannerForTests()
        val snap = host.libraryReadBannerForTests()
        assertFalse(snap.visible)
        assertFalse(snap.pickDialogVisible)
    }

    @Test
    fun launch_pick_when_denied_shows_dialog_not_picker() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.requestPickPhotosForTests()
        assertEquals(0, pickCalls)
        val snap = host.libraryReadBannerForTests()
        assertTrue(snap.pickDialogVisible)
        assertEquals(LibraryReadBannerKind.Denied, snap.kind)
    }

    @Test
    fun continue_opens_picker_and_skips_until_next_launch_visit() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.requestPickPhotosForTests()
        host.continueLibraryReadPickDialogForTests()
        assertEquals(1, pickCalls)
        assertFalse(host.libraryReadBannerForTests().pickDialogVisible)
        host.requestPickPhotosForTests()
        assertEquals(2, pickCalls)
        assertFalse(host.libraryReadBannerForTests().pickDialogVisible)
        host.showEditorShellImmediately()
        host.leaveEditorForTests()
        host.requestPickPhotosForTests()
        assertEquals(2, pickCalls)
        assertTrue(host.libraryReadBannerForTests().pickDialogVisible)
    }

    @Test
    fun limited_dialog_cta_opens_settings_not_limited_picker() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Limited)
        host.requestPickPhotosForTests()
        assertEquals(LibraryReadBannerKind.Limited, host.libraryReadBannerForTests().kind)
        host.openLibraryReadBannerCtaForTests()
        val url = host.libraryReadBannerForTests().lastCtaUrl
        assertEquals(UIApplicationOpenSettingsURLString, url)
        assertEquals(listOf(UIApplicationOpenSettingsURLString), openedUrls)
        assertFalse(url.orEmpty().contains("presentLimitedLibraryPicker"))
        assertEquals(0, pickCalls)
        host.simulateAppBecameActiveForTests()
        assertEquals(0, pickCalls)
        assertFalse(host.libraryReadBannerForTests().pickDialogVisible)
    }

    @Test
    fun authorized_pick_opens_picker_immediately() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Authorized)
        host.requestPickPhotosForTests()
        assertEquals(1, pickCalls)
        assertFalse(host.libraryReadBannerForTests().pickDialogVisible)
        assertNull(host.libraryReadBannerForTests().kind)
    }

    @Test
    fun not_determined_does_not_show_dialog_before_prompt() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.NotDetermined)
        host.refreshLibraryReadBannerForTests()
        assertFalse(host.libraryReadBannerForTests().pickDialogVisible)
    }

    @Test
    fun editor_add_more_uses_same_dialog() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.showEditorShellImmediately()
        assertFalse(host.libraryReadBannerForTests().visible)
        host.requestPickPhotosForTests()
        assertEquals(0, pickCalls)
        assertTrue(host.libraryReadBannerForTests().pickDialogVisible)
        host.continueLibraryReadPickDialogForTests()
        assertEquals(1, pickCalls)
    }

    @Test
    fun allow_all_after_settings_closes_dialog() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.requestPickPhotosForTests()
        assertTrue(host.libraryReadBannerForTests().pickDialogVisible)
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Authorized)
        host.simulateAppBecameActiveForTests()
        assertFalse(host.libraryReadBannerForTests().pickDialogVisible)
        assertEquals(1, pickCalls)
    }

    @Test
    fun restricted_uses_settings_kind() = runBlocking {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Restricted)
        host.requestPickPhotosForTests()
        assertEquals(LibraryReadBannerKind.Restricted, host.libraryReadBannerForTests().kind)
        host.openLibraryReadBannerCtaForTests()
        assertEquals(UIApplicationOpenSettingsURLString, host.libraryReadBannerForTests().lastCtaUrl)
    }
}
