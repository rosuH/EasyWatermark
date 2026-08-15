@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.rosuh.easywatermark.ui

import androidx.lifecycle.ViewModelStore
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
 * ADR-0029 P5 / Q11=B — Host Library Read upsell. Status is injected; this is not
 * a Photos authorization runtime proof.
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
        host = IosProductRootHost(
            onPickPhoto = {},
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
    fun denied_enter_editor_shows_banner() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.showEditorShellImmediately()
        val snap = host.libraryReadBannerForTests()
        assertTrue(snap.visible)
        assertEquals(LibraryReadBannerKind.Denied, snap.kind)
        assertFalse(snap.dismissedThisVisit)
    }

    @Test
    fun limited_cta_opens_settings_not_limited_picker() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Limited)
        host.showEditorShellImmediately()
        assertEquals(LibraryReadBannerKind.Limited, host.libraryReadBannerForTests().kind)
        host.openLibraryReadBannerCtaForTests()
        val url = host.libraryReadBannerForTests().lastCtaUrl
        assertEquals(UIApplicationOpenSettingsURLString, url)
        assertEquals(listOf(UIApplicationOpenSettingsURLString), openedUrls)
        assertFalse(url.orEmpty().contains("presentLimitedLibraryPicker"))
    }

    @Test
    fun dismiss_hides_until_leave_and_reenter() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.showEditorShellImmediately()
        assertTrue(host.libraryReadBannerForTests().visible)
        host.dismissLibraryReadBannerForTests()
        val afterDismiss = host.libraryReadBannerForTests()
        assertFalse(afterDismiss.visible)
        assertTrue(afterDismiss.dismissedThisVisit)
        host.leaveEditorForTests()
        assertFalse(host.libraryReadBannerForTests().dismissedThisVisit)
        host.showEditorShellImmediately()
        assertTrue(host.libraryReadBannerForTests().visible)
    }

    @Test
    fun authorized_never_shows() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Authorized)
        host.showEditorShellImmediately()
        assertFalse(host.libraryReadBannerForTests().visible)
        assertNull(host.libraryReadBannerForTests().kind)
    }

    @Test
    fun not_determined_does_not_show_before_prompt() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.NotDetermined)
        host.showEditorShellImmediately()
        assertFalse(host.libraryReadBannerForTests().visible)
    }

    @Test
    fun request_denied_while_in_editor_shows_this_visit() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.NotDetermined)
        host.showEditorShellImmediately()
        assertFalse(host.libraryReadBannerForTests().visible)
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        IosPhotoLibraryAccess.markRequestedForTests()
        host.refreshLibraryReadBannerForTests()
        assertTrue(host.libraryReadBannerForTests().visible)
        assertEquals(LibraryReadBannerKind.Denied, host.libraryReadBannerForTests().kind)
    }

    @Test
    fun allow_all_after_settings_hides_immediately() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Denied)
        host.showEditorShellImmediately()
        assertTrue(host.libraryReadBannerForTests().visible)
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Authorized)
        host.simulateAppBecameActiveForTests()
        assertFalse(host.libraryReadBannerForTests().visible)
    }

    @Test
    fun restricted_uses_settings_kind() {
        IosPhotoLibraryAccess.installStatusForTests(IosPhotoLibraryAccess.Status.Restricted)
        host.showEditorShellImmediately()
        assertEquals(LibraryReadBannerKind.Restricted, host.libraryReadBannerForTests().kind)
        host.openLibraryReadBannerCtaForTests()
        assertEquals(UIApplicationOpenSettingsURLString, host.libraryReadBannerForTests().lastCtaUrl)
    }
}
