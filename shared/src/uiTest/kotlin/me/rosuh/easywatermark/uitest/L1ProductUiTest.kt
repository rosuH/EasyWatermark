package me.rosuh.easywatermark.uitest

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_follow_photo
import me.rosuh.easywatermark.shared.generated.resources.about_follow_wallpaper
import me.rosuh.easywatermark.shared.generated.resources.about_prefer_in_app_gallery
import me.rosuh.easywatermark.shared.generated.resources.about_show_bounds
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.about_title_open_source
import me.rosuh.easywatermark.shared.generated.resources.tips_choose_color_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.ui.ProductShellNav
import me.rosuh.easywatermark.ui.compose.formatArgbHexColor
import me.rosuh.easywatermark.ui.compose.parseArgbHexColor
import org.jetbrains.compose.resources.getString

/**
 * ADR-0032 P1 L1 cases. Drive the real shared tree via [L1ProductTree].
 *
 * API: CMP 1.12 `androidx.compose.ui.test.v2.runComposeUiTest` (still opt-in
 * [ExperimentalTestApi] per current CMP docs).
 */
@OptIn(ExperimentalTestApi::class)
class L1ProductUiTest {

    @Test
    fun seamFeedShowsEditor() = runComposeUiTest {
        L1Live.activeTest = "seamFeedShowsEditor"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchPickImageButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeWatermarkPreview", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(ProductShellNav.Route.Editor, session.route)
        assertEquals(session.fixtureImage.uri, session.selected?.uri)
        captureL1Witness("seamFeedShowsEditor")
    }

    @Test
    fun editorTabAndTileModeUpdatesConfig() = runComposeUiTest {
        L1Live.activeTest = "editorTabAndTileModeUpdatesConfig"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        awaitIdle()
        waitForTag("sharedComposeEditorScreen")
        waitUntil(timeoutMillis = 5_000) {
            session.repoWaterMarkBound && session.waterMark.tileMode == WatermarkTileMode.REPEAT
        }
        assertEquals(WatermarkTileMode.REPEAT, session.waterMark.tileMode)
        onNodeWithTag("editorTab-1", useUnmergedTree = true).performClick()
        awaitIdle()
        waitForTag("editorControl-TileMode")
        onNodeWithTag("choice-1", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.waterMark.tileMode == WatermarkTileMode.CLAMP
        }
        assertEquals(WatermarkTileMode.CLAMP, session.waterMark.tileMode)
        captureL1Witness("editorTabAndTileModeUpdatesConfig")
    }

    @Test
    fun editorTypefaceBoldUpdatesConfig() = runComposeUiTest {
        L1Live.activeTest = "editorTypefaceBoldUpdatesConfig"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        waitUntil(timeoutMillis = 5_000) { session.repoWaterMarkBound }
        onNodeWithTag("editorTab-1", useUnmergedTree = true).performClick()
        awaitIdle()
        onNodeWithTag("editorOption-TextTypeFace", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("editorControl-TextTypeFace")
        onNodeWithText("Bold", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.waterMark.textTypeface == TextTypeface.Bold
        }
        assertEquals(TextTypeface.Bold, session.waterMark.textTypeface)
        captureL1Witness("editorTypefaceBoldUpdatesConfig")
    }

    @Test
    fun editorFilmstripSwitchChangesFocus() = runComposeUiTest {
        L1Live.activeTest = "editorFilmstripSwitchChangesFocus"
        val session = L1Session()
        session.seamFeedImage()
        session.addMoreImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        waitForTag("editorMainFilmstrip")
        assertEquals(2, session.images.size)
        val first = session.selected?.uri
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithContentDescription("image", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size >= 2
        }
        onAllNodesWithContentDescription("image", useUnmergedTree = true)[1].performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.selected?.uri != first }
        assertEquals(session.extraImage.uri, session.selected?.uri)
        captureL1Witness("editorFilmstripSwitchChangesFocus")
    }

    @Test
    fun aboutFollowPhotoToggle() = runComposeUiTest {
        L1Live.activeTest = "aboutFollowPhotoToggle"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchAboutButton", useUnmergedTree = true).performClick()
        waitForTag("aboutBack")
        assertTrue(session.followPhotoOn)
        val followPhoto = l1String(Res.string.about_follow_photo)
        onNodeWithText(followPhoto, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { !session.followPhotoOn }
        assertTrue(!session.followPhotoOn)
        captureL1Witness("aboutFollowPhotoToggle", "aboutBack")
    }

    @Test
    fun aboutInAppGalleryToggle() = runComposeUiTest {
        L1Live.activeTest = "aboutInAppGalleryToggle"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchAboutButton", useUnmergedTree = true).performClick()
        waitForTag("aboutBack")
        assertTrue(!session.preferInAppGallery)
        val galleryLabel = l1String(Res.string.about_prefer_in_app_gallery)
        onNodeWithText(galleryLabel, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.preferInAppGallery }
        assertTrue(session.preferInAppGallery)
        captureL1Witness("aboutInAppGalleryToggle", "aboutBack")
    }

    @Test
    fun templateSaveApplyDelete() = runComposeUiTest {
        L1Live.activeTest = "templateSaveApplyDelete"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("watermarkTextContent", useUnmergedTree = true).performClick()
        waitForTag("watermarkTextTemplateIcon")
        onNodeWithTag("watermarkTextTemplateIcon", useUnmergedTree = true).performClick()
        waitForTag("templateListSheet")
        onNodeWithTag("templateAddButton", useUnmergedTree = true).performClick()
        waitForTag("templateEditField")
        onNodeWithTag("templateEditField", useUnmergedTree = true)
            .performTextReplacement("L1-tpl")
        onNodeWithTag("templateEditConfirm", useUnmergedTree = true).performClick()
        waitForTag("templateRow-1")
        assertEquals(1, session.templates.size)
        assertEquals("L1-tpl", session.templates.single().content)
        onNodeWithTag("templateRow-1", useUnmergedTree = true).performClick()
        waitForTag("templateUseConfirm")
        onNodeWithTag("templateUseConfirm", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.waterMark.text == "L1-tpl" }
        assertEquals("L1-tpl", session.waterMark.text)
        onNodeWithTag("watermarkTextContent", useUnmergedTree = true).performClick()
        waitForTag("watermarkTextTemplateIcon")
        onNodeWithTag("watermarkTextTemplateIcon", useUnmergedTree = true).performClick()
        waitForTag("templateListSheet")
        onNodeWithTag("templateDeleteButton-1", useUnmergedTree = true).performClick()
        waitForTag("templateDeleteConfirm")
        onNodeWithTag("templateDeleteConfirm", useUnmergedTree = true).performClick()
        assertTrue(session.templates.isEmpty())
        captureL1Witness("templateSaveApplyDelete")
    }

    @Test
    fun textEditSheetUpdatesWatermark() = runComposeUiTest {
        L1Live.activeTest = "textEditSheetUpdatesWatermark"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("watermarkTextContent", useUnmergedTree = true).performClick()
        waitForTag("watermarkTextEditField")
        onNodeWithTag("watermarkTextEditField", useUnmergedTree = true)
            .performTextReplacement("L1-text")
        onNodeWithTag("watermarkTextConfirm", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.waterMark.text == "L1-text" }
        assertEquals("L1-text", session.waterMark.text)
        captureL1Witness("textEditSheetUpdatesWatermark")
    }

    @Test
    fun exportViaFakePortShowsSuccess() = runComposeUiTest {
        L1Live.activeTest = "exportViaFakePortShowsSuccess"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeExportSheet")
        onNodeWithTag("sharedComposeExportPrimary", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.exportPort.calls == 1 && session.exportRecovery.isAllSuccess
        }
        // Status is a 0.dp a11y Spacer (never painted); assert the tag + live-region CD.
        waitForTag("sharedComposeExportStatus")
        onNodeWithTag("sharedComposeExportStatus", useUnmergedTree = true)
            .assertContentDescriptionContains("1 succeeded", substring = true)
        captureL1Witness("exportViaFakePortShowsSuccess", "sharedComposeExportSheet")
    }

    @Test
    fun exportFormatJpegPngSwitchesPrefs() = runComposeUiTest {
        L1Live.activeTest = "exportFormatJpegPngSwitchesPrefs"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeExportSheet")
        assertEquals(ImageFormat.JPEG, session.outputPrefs.outputFormat)
        onNodeWithText("PNG", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.outputPrefs.outputFormat == ImageFormat.PNG
        }
        assertEquals(ImageFormat.PNG, session.outputPrefs.outputFormat)
        onNodeWithText("JPEG", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.outputPrefs.outputFormat == ImageFormat.JPEG
        }
        assertEquals(ImageFormat.JPEG, session.outputPrefs.outputFormat)
        captureL1Witness("exportFormatJpegPngSwitchesPrefs", "sharedComposeExportSheet")
    }

    @Test
    fun aboutOpenSourceRoundTrip() = runComposeUiTest {
        L1Live.activeTest = "aboutOpenSourceRoundTrip"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchAboutButton", useUnmergedTree = true).performClick()
        waitForTag("aboutBack")
        val openSource = l1String(Res.string.about_title_open_source)
        onNodeWithText(openSource, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("openSourceContentMaxWidth")
        waitForTag("openSourceBack")
        assertTrue(session.showOpenSource)
        onNodeWithTag("openSourceBack", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { !session.showOpenSource }
        waitForTag("aboutBack")
        captureL1Witness("aboutOpenSourceRoundTrip", "aboutBack")
    }

    @Test
    fun aboutWallpaperToggle() = runComposeUiTest {
        L1Live.activeTest = "aboutWallpaperToggle"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchAboutButton", useUnmergedTree = true).performClick()
        waitForTag("aboutBack")
        assertTrue(!session.followWallpaperOn)
        val wallpaper = l1String(Res.string.about_follow_wallpaper)
        onNodeWithText(wallpaper, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.followWallpaperOn }
        assertTrue(session.followWallpaperOn)
        captureL1Witness("aboutWallpaperToggle", "aboutBack")
    }

    @Test
    fun aboutBoundsToggle() = runComposeUiTest {
        L1Live.activeTest = "aboutBoundsToggle"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchAboutButton", useUnmergedTree = true).performClick()
        waitForTag("aboutBack")
        assertTrue(!session.showBounds)
        val bounds = l1String(Res.string.about_show_bounds)
        onNodeWithText(bounds, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.showBounds }
        assertTrue(session.showBounds)
        captureL1Witness("aboutBoundsToggle", "aboutBack")
    }

    @Test
    fun editorClampDragUpdatesOffset() = runComposeUiTest {
        L1Live.activeTest = "editorClampDragUpdatesOffset"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        waitUntil(timeoutMillis = 5_000) { session.repoWaterMarkBound }
        onNodeWithTag("editorTab-1", useUnmergedTree = true).performClick()
        awaitIdle()
        waitForTag("editorControl-TileMode")
        onNodeWithTag("choice-1", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.waterMark.tileMode == WatermarkTileMode.CLAMP
        }
        val beforeX = session.selected?.offsetX
        val beforeY = session.selected?.offsetY
        waitForTag("sharedComposeWatermarkPreview")
        onNodeWithTag("sharedComposeWatermarkPreview", useUnmergedTree = true)
            .performTouchInput {
                val start = Offset(visibleSize.width * 0.5f, visibleSize.height * 0.5f)
                val end = Offset(visibleSize.width * 0.8f, visibleSize.height * 0.5f)
                down(start)
                moveTo(end)
                up()
            }
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.selected?.offsetX != beforeX || session.selected?.offsetY != beforeY
        }
        assertNotEquals(beforeX, session.selected?.offsetX)
        captureL1Witness("editorClampDragUpdatesOffset")
    }

    @Test
    fun exportCancelStopsHeldJob() = runComposeUiTest {
        L1Live.activeTest = "exportCancelStopsHeldJob"
        val session = L1Session()
        session.seamFeedImage()
        session.holdExport = true
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeExportSheet")
        onNodeWithTag("sharedComposeExportPrimary", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { session.exportRecovery.showCancel }
        waitForTag("sharedComposeExportCancel")
        onNodeWithTag("sharedComposeExportCancel", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.exportCancelled && !session.exportRecovery.isExporting
        }
        assertTrue(session.exportCancelled)
        assertTrue(!session.exportRecovery.showCancel)
        captureL1Witness("exportCancelStopsHeldJob", "sharedComposeExportSheet")
    }

    @Test
    fun editorStyleThenExport() = runComposeUiTest {
        L1Live.activeTest = "editorStyleThenExport"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        waitUntil(timeoutMillis = 5_000) { session.repoWaterMarkBound }
        val sizeBefore = session.waterMark.textSize
        val alphaBefore = session.waterMark.alpha
        onNodeWithTag("watermarkTextContent", useUnmergedTree = true).performClick()
        waitForTag("watermarkTextEditField")
        onNodeWithTag("watermarkTextEditField", useUnmergedTree = true)
            .performTextReplacement("L1-combo")
        onNodeWithTag("watermarkTextConfirm", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.waterMark.text == "L1-combo" }
        onNodeWithTag("editorTab-1", useUnmergedTree = true).performClick()
        awaitIdle()
        onNodeWithTag("editorOption-TextSize", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("editorControl-TextSize")
        onNodeWithTag("sliderTrack", useUnmergedTree = true).performTouchInput {
            val w = visibleSize.width.toFloat()
            val h = visibleSize.height.toFloat()
            down(Offset(w * 0.9f, h * 0.5f))
            moveTo(Offset(w * 0.9f, h * 0.5f))
            up()
        }
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.waterMark.textSize != sizeBefore }
        revealTag("editorOption-Color", swipeFromTag = "editorOption-TileMode")
        onNodeWithTag("editorOption-Color", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("colorSwatch-FF000000")
        onNodeWithTag("colorSwatch-FF000000", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.waterMark.textColor == 0xFF000000.toInt()
        }
        revealTag("editorOption-Alpha", swipeFromTag = "editorOption-TileMode")
        onNodeWithTag("editorOption-Alpha", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("editorControl-Alpha")
        onNodeWithTag("sliderTrack", useUnmergedTree = true).performTouchInput {
            val w = visibleSize.width.toFloat()
            val h = visibleSize.height.toFloat()
            down(Offset(w * 0.2f, h * 0.5f))
            moveTo(Offset(w * 0.2f, h * 0.5f))
            up()
        }
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.waterMark.alpha != alphaBefore }
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeExportSheet")
        onNodeWithTag("sharedComposeExportPrimary", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.exportPort.calls == 1 && session.exportRecovery.isAllSuccess
        }
        assertEquals("L1-combo", session.waterMark.text)
        assertTrue(session.waterMark.textSize > sizeBefore)
        assertEquals(0xFF000000.toInt(), session.waterMark.textColor)
        assertTrue(session.waterMark.alpha < alphaBefore)
        captureL1Witness("editorStyleThenExport", "sharedComposeExportSheet")
    }

    @Test
    fun aboutOverlayRoundTrip() = runComposeUiTest {
        L1Live.activeTest = "aboutOverlayRoundTrip"
        val session = L1Session()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchAboutButton", useUnmergedTree = true).performClick()
        waitForTag("aboutBack")
        onNodeWithTag("sharedComposeLaunchScreen", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(ProductShellNav.Route.About, session.route)
        onNodeWithTag("aboutBack", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.route == ProductShellNav.Route.Launch
        }
        onNodeWithTag("sharedComposeLaunchScreen", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("launchPickImageButton", useUnmergedTree = true).assertIsDisplayed()
        captureL1Witness("aboutOverlayRoundTrip", "sharedComposeLaunchScreen")
    }

    @Test
    fun exportFailureRecovery() = runComposeUiTest {
        L1Live.activeTest = "exportFailureRecovery"
        val session = L1Session()
        session.seamFeedImage()
        session.exportPort.failNext = true
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeExportSheet")
        onNodeWithTag("sharedComposeExportPrimary", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.exportPort.calls == 1 && session.exportRecovery.showRetryFailed
        }
        waitForTag("sharedComposeExportRetryFailed")
        onNodeWithTag("sharedComposeExportStatus", useUnmergedTree = true)
            .assertContentDescriptionContains("1 failed", substring = true)
        onNodeWithTag("sharedComposeExportRetryFailed", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.exportPort.calls == 2 && session.exportRecovery.isAllSuccess
        }
        onNodeWithTag("sharedComposeExportStatus", useUnmergedTree = true)
            .assertContentDescriptionContains("1 succeeded", substring = true)
        captureL1Witness("exportFailureRecovery", "sharedComposeExportSheet")
    }

    @Test
    fun editorDualPaneMorph() = runSkikoComposeUiTest(
        size = Size(1024f, 768f),
        density = Density(1f),
    ) {
        L1Live.activeTest = "editorDualPaneMorph"
        val session = L1Session()
        session.seamFeedImage()
        setContent {
            L1ProductTree(session, Modifier.size(L1ExpandedWidthDp.dp, L1ExpandedHeightDp.dp))
        }
        waitForTag("sharedComposeEditorScreen")
        awaitIdle()
        waitForTag("editorLayoutExpanded")
        waitForTag("editorExpandedPaneRow")
        waitUntil(timeoutMillis = 5_000) {
            !tagAbsent("editorExpandedControlsPane") || !tagAbsent("editorInspectorPanel")
        }
        assertTrue(tagAbsent("editorLayoutCompact"))
        setContent {
            L1ProductTree(session, Modifier.size(L1CompactWidthDp.dp, L1CompactHeightDp.dp))
        }
        waitForTag("sharedComposeEditorScreen")
        waitForTag("editorLayoutCompact")
        assertTrue(tagAbsent("editorExpandedControlsPane"))
        assertTrue(tagAbsent("editorExpandedPaneRow"))
        captureL1Witness("editorDualPaneMorph")
    }

    @Test
    fun customColorSheetUpdatesWatermark() = runComposeUiTest {
        L1Live.activeTest = "customColorSheetUpdatesWatermark"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        waitUntil(timeoutMillis = 5_000) { session.repoWaterMarkBound }
        val before = session.waterMark.textColor
        val chooseColor = l1String(Res.string.tips_choose_color_dialog)
        val confirm = l1String(Res.string.tips_confirm_dialog)
        onNodeWithTag("editorTab-1", useUnmergedTree = true).performClick()
        awaitIdle()
        revealTag("editorOption-Color", swipeFromTag = "editorOption-TileMode")
        onNodeWithTag("editorOption-Color", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("colorSwatch-custom")
        onNodeWithTag("colorSwatch-custom", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(chooseColor, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val customHex = "00AABB"
        val currentHex = formatArgbHexColor(before).removePrefix("#").takeLast(6)
        onNodeWithText(currentHex, useUnmergedTree = true).performTextReplacement(customHex)
        onNodeWithText(confirm, useUnmergedTree = true).performClick()
        val expected = parseArgbHexColor(customHex)
        waitUntil(timeoutMillis = 5_000) { session.waterMark.textColor == expected }
        assertEquals(expected, session.waterMark.textColor)
        assertNotEquals(before, session.waterMark.textColor)
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(chooseColor, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        captureL1Witness("customColorSheetUpdatesWatermark")
    }

    @Test
    fun inAppGalleryPickEntersEditor() = runComposeUiTest {
        L1Live.activeTest = "inAppGalleryPickEntersEditor"
        val session = L1Session()
        session.preferInAppGallery = true
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchPickImageButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeGalleryDialog")
        waitForTag("galleryImageCard")
        onAllNodesWithTag("galleryImageCard", useUnmergedTree = true)[0].performClick()
        waitForTag("sharedComposeGalleryConfirm")
        onNodeWithTag("sharedComposeGalleryConfirm", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.route == ProductShellNav.Route.Editor && session.images.isNotEmpty()
        }
        waitForTag("sharedComposeEditorScreen")
        assertEquals(MediaRef("mem://gal-1"), session.selected?.uri)
        captureL1Witness("inAppGalleryPickEntersEditor")
    }

    @Test
    fun shareInSelectionOpensEditor() = runComposeUiTest {
        L1Live.activeTest = "shareInSelectionOpensEditor"
        val session = L1Session()
        session.shareInSelection()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeWatermarkPreview", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(ProductShellNav.Route.Editor, session.route)
        assertEquals(session.fixtureImage.uri, session.selected?.uri)
        captureL1Witness("shareInSelectionOpensEditor")
    }

    @Test
    fun iconOptionPickUpdatesWatermark() = runComposeUiTest {
        L1Live.activeTest = "iconOptionPickUpdatesWatermark"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        waitUntil(timeoutMillis = 5_000) { session.repoWaterMarkBound }
        onNodeWithTag("editorOption-Icon", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        awaitIdle()
        waitForTag("sharedComposeIconWatermarkOption")
        onNodeWithTag("sharedComposeIconWatermarkOption", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) {
            session.waterMark.iconUri == MediaRef("mem://l1-icon") &&
                session.waterMark.markMode == WatermarkMode.Image
        }
        assertEquals(MediaRef("mem://l1-icon"), session.waterMark.iconUri)
        assertEquals(WatermarkMode.Image, session.waterMark.markMode)
        captureL1Witness("iconOptionPickUpdatesWatermark")
    }

    @Test
    fun addMoreAppendsSecondImage() = runComposeUiTest {
        L1Live.activeTest = "addMoreAppendsSecondImage"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        assertEquals(1, session.images.size)
        onNodeWithTag("sharedComposeAddMoreButton", useUnmergedTree = true).performClick()
        awaitIdle()
        waitUntil(timeoutMillis = 5_000) { session.images.size == 2 }
        assertEquals(session.extraImage.uri, session.images.last().uri)
        captureL1Witness("addMoreAppendsSecondImage")
    }

    @Test
    fun exportOpenGalleryAfterSuccess() = runComposeUiTest {
        L1Live.activeTest = "exportOpenGalleryAfterSuccess"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).performClick()
        waitForTag("sharedComposeExportSheet")
        onNodeWithTag("sharedComposeExportPrimary", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.exportPort.calls == 1 && session.exportRecovery.isAllSuccess
        }
        waitForTag("sharedComposeExportOpenGallery")
        onNodeWithTag("sharedComposeExportOpenGallery", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) { session.openGalleryClicks == 1 }
        assertEquals(1, session.openGalleryClicks)
        captureL1Witness("exportOpenGalleryAfterSuccess", "sharedComposeExportSheet")
    }

    @Test
    fun recoveryScreenCloseReturns() = runComposeUiTest {
        L1Live.activeTest = "recoveryScreenCloseReturns"
        val session = L1Session()
        session.showRecovery = true
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeRecoveryScreen")
        onNodeWithTag("sharedComposeRecoveryClose", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.recoveryClosed && !session.showRecovery
        }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchPickImageButton", useUnmergedTree = true).assertIsDisplayed()
        captureL1Witness("recoveryScreenCloseReturns", "sharedComposeLaunchScreen")
    }

    @Test
    fun libraryReadUpsellContinueSeamsPick() = runComposeUiTest {
        L1Live.activeTest = "libraryReadUpsellContinueSeamsPick"
        val session = L1Session()
        session.libraryReadDenied = true
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeLaunchScreen")
        onNodeWithTag("launchPickImageButton", useUnmergedTree = true).performClick()
        waitForTag("iosLibraryReadUpsellContinue")
        onNodeWithTag("iosLibraryReadUpsellContinue", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.route == ProductShellNav.Route.Editor
        }
        waitForTag("sharedComposeEditorScreen")
        assertEquals(session.fixtureImage.uri, session.selected?.uri)
        captureL1Witness("libraryReadUpsellContinueSeamsPick")
    }

    @Test
    fun editorAboutOverlayRoundTrip() = runComposeUiTest {
        L1Live.activeTest = "editorAboutOverlayRoundTrip"
        val session = L1Session()
        session.seamFeedImage()
        setContent { L1ProductTree(session) }
        waitForTag("sharedComposeEditorScreen")
        val aboutCd = l1String(Res.string.about_title_about)
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithContentDescription(aboutCd, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        onAllNodesWithContentDescription(aboutCd, useUnmergedTree = true)[0].performClick()
        waitForTag("aboutBack")
        onNodeWithTag("sharedComposeEditorScreen", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(ProductShellNav.Route.About, session.route)
        onNodeWithTag("aboutBack", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            session.route == ProductShellNav.Route.Editor
        }
        onNodeWithTag("sharedComposeEditorScreen", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("sharedComposeSaveButton", useUnmergedTree = true).assertIsDisplayed()
        captureL1Witness("editorAboutOverlayRoundTrip")
    }

    private fun ComposeUiTest.waitForTag(
        tag: String,
        timeoutMs: Long = 5_000,
    ) {
        var lastPreview = TimeSource.Monotonic.markNow() - 2_000.milliseconds
        waitUntil(timeoutMillis = timeoutMs) {
            val found = onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (found) {
                captureL1Live(tag, keyframe = true)
                return@waitUntil true
            }
            if (lastPreview.elapsedNow() >= 750.milliseconds) {
                lastPreview = TimeSource.Monotonic.markNow()
                captureL1LiveBestEffort(keyframe = false)
            }
            false
        }
    }

    private fun ComposeUiTest.tagAbsent(tag: String): Boolean =
        onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isEmpty()

    /** Resolve a product string under the process locale (uiTest is common; no JVM Locale). */
    private fun l1String(resource: org.jetbrains.compose.resources.StringResource): String =
        runBlocking { getString(resource) }

    private fun ComposeUiTest.revealTag(
        tag: String,
        swipeFromTag: String,
        attempts: Int = 6,
    ) {
        waitUntil(timeoutMillis = 5_000) {
            if (!tagAbsent(tag)) return@waitUntil true
            if (!tagAbsent(swipeFromTag)) {
                onNodeWithTag(swipeFromTag, useUnmergedTree = true)
                    .performTouchInput { swipeLeft() }
            }
            false
        }
        assertTrue(!tagAbsent(tag), "expected $tag after $attempts carousel swipes")
    }
}
