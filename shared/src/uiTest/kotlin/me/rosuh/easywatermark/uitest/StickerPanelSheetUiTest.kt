package me.rosuh.easywatermark.uitest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.render.WatermarkIconCache
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.preview_selection_done
import me.rosuh.easywatermark.shared.generated.resources.sticker_all_open
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_batch_confirm
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_selected
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_title
import me.rosuh.easywatermark.shared.generated.resources.sticker_deselect_all
import me.rosuh.easywatermark.shared.generated.resources.sticker_import
import me.rosuh.easywatermark.shared.generated.resources.sticker_manage
import me.rosuh.easywatermark.shared.generated.resources.sticker_none
import me.rosuh.easywatermark.shared.generated.resources.sticker_select_all
import me.rosuh.easywatermark.shared.generated.resources.sticker_selected_count
import me.rosuh.easywatermark.shared.generated.resources.template_delete
import me.rosuh.easywatermark.shared.generated.resources.tips_cancel_dialog
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_image
import me.rosuh.easywatermark.ui.EditorBottomOverlayHostState
import me.rosuh.easywatermark.ui.EditorLayoutClass
import me.rosuh.easywatermark.ui.EditorOptionItem
import me.rosuh.easywatermark.ui.EditorScreen
import me.rosuh.easywatermark.ui.EditorUiIcons
import me.rosuh.easywatermark.ui.LocalEditorBottomOverlayHost
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.compose.BuiltInSticker
import me.rosuh.easywatermark.ui.compose.StickerPanelOnIcon
import me.rosuh.easywatermark.ui.compose.toStickerBitmap
import me.rosuh.easywatermark.ui.iconPainter
import me.rosuh.easywatermark.ui.label
import me.rosuh.easywatermark.ui.theme.AppTheme
import me.rosuh.easywatermark.ui.usesLargeScreenDialog
import org.jetbrains.compose.resources.stringResource

/**
 * Sticker panel v3 contracts (spec sections 1.1–1.4, C1, C2, C4).
 *
 * Written against the contracts, not against an implementation: tiles are addressed by the C4 tags,
 * "applied" state by `selected` semantics, and "ignores input" by the absence of the host callback.
 */

// ---------------------------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------------------------

/** Live sticker state plus a recorder for every [StickerPanelOnIcon] callback. */
internal class StickerHarness(
    icon: MediaRef = MediaRef.Empty,
    stickers: List<Sticker> = emptyList(),
) {
    var mark by mutableStateOf(
        WaterMark.default.copy(markMode = WatermarkMode.Image, iconUri = icon),
    )
    var stickers by mutableStateOf(stickers)
    var adds = 0
    var clears = 0
    val appliedBuiltIns = mutableListOf<BuiltInSticker>()
    val appliedUsers = mutableListOf<Sticker>()
    val deletedSingles = mutableListOf<Sticker>()
    val deletedBatches = mutableListOf<List<Sticker>>()

    fun builtInRef(builtIn: BuiltInSticker) = MediaRef("/tmp/icon-builtin-${builtIn.key}.png")
}

@Composable
internal fun StickerPanelHost(
    harness: StickerHarness,
    useLargeDialog: Boolean,
    modifier: Modifier = Modifier,
) {
    StickerPanelOnIcon(
        waterMark = harness.mark,
        userStickers = harness.stickers,
        modifier = modifier,
        useLargeDialog = useLargeDialog,
        onAdd = { harness.adds += 1 },
        onApplyBuiltIn = { builtIn, _ ->
            harness.appliedBuiltIns += builtIn
            harness.mark = harness.mark.copy(iconUri = harness.builtInRef(builtIn))
        },
        onApplyUser = { sticker ->
            harness.appliedUsers += sticker
            harness.mark = harness.mark.copy(iconUri = sticker.icon)
        },
        onDeleteUser = { sticker ->
            harness.deletedSingles += sticker
            harness.stickers = harness.stickers - sticker
        },
        onDeleteUsers = { targets ->
            harness.deletedBatches += targets
            val ids = targets.map { it.id }.toSet()
            harness.stickers = harness.stickers.filterNot { it.id in ids }
        },
        onClear = {
            harness.clears += 1
            harness.mark = harness.mark.copy(iconUri = MediaRef.Empty)
        },
    )
}

/**
 * Compact docked host: live canvas above, rail at the bottom, overlay content docked in a
 * fixed-height slot — the C1 container shape, without pulling in the whole editor shell.
 */
@Composable
private fun DockedStickerScaffold(
    host: EditorBottomOverlayHostState,
    harness: StickerHarness,
    onCanvasClick: () -> Unit = {},
    width: Dp = 360.dp,
) {
    CompositionLocalProvider(LocalEditorBottomOverlayHost provides host) {
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { onCanvasClick() }
                        .testTag("testLiveCanvas"),
                )
                StickerPanelHost(harness = harness, useLargeDialog = false)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(360.dp),
            ) {
                host.content?.invoke()
            }
        }
    }
}

/** Real editor shell with the Icon option forced open (C1); compact unless [layoutClass] says otherwise. */
@Composable
internal fun EditorScreenUnderTest(
    harness: StickerHarness,
    photo: ImageInfoUi,
    modifier: Modifier = Modifier,
    onCanvasClick: () -> Unit = {},
    /** Witness frames swap in a photo-like fill so the C1 refit is visible. */
    preview: @Composable (Modifier) -> Unit = { previewModifier ->
        Box(
            modifier = previewModifier
                .clickable { onCanvasClick() }
                .testTag("testEditorCanvas"),
        )
    },
    layoutClass: EditorLayoutClass = EditorLayoutClass.Compact,
    overlayBackHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    Box(modifier = modifier) {
        EditorScreen(
            imageList = listOf(photo),
            waterMark = harness.mark,
            selectedImage = photo,
            templates = emptyList(),
            icons = EditorUiIcons(
                back = SharedProductDrawables.backPainter(),
                addMoreImages = SharedProductDrawables.pickerImagePainter(),
                save = SharedProductDrawables.savePainter(),
                about = SharedProductDrawables.aboutPainter(),
                templateList = SharedProductDrawables.templateListPainter(),
            ),
            preview = preview,
            thumbnail = { _, _, thumbModifier -> Box(modifier = thumbModifier) },
            optionItem = { spec, selected ->
                val label = spec.type.label()
                EditorOptionItem(
                    icon = spec.type.iconPainter(),
                    contentDescription = label,
                    label = label,
                    selected = selected,
                )
            },
            colorOption = { _, _, _ -> },
            iconOption = { optionModifier, _, _ ->
                StickerPanelHost(
                    harness = harness,
                    useLargeDialog = usesLargeScreenDialog(layoutClass),
                    modifier = optionModifier,
                )
            },
            onBack = {},
            onAddMoreImages = {},
            onShowSaveDialog = {},
            onGoAboutScreen = {},
            onImageSelected = {},
            onConfigChange = {},
            onUseTemplate = {},
            onAddTemplate = {},
            onUpdateTemplate = {},
            onDeleteTemplate = {},
            layoutClass = layoutClass,
            forcedBottomTab = 0,
            forcedOptionIndex = 1,
            overlayBackHandler = overlayBackHandler,
        )
    }
}

/** Painter with a known intrinsic aspect ratio and a known colour (C2 `toStickerBitmap`). */
private class SolidPainter(
    override val intrinsicSize: Size,
    private val color: Color,
) : Painter() {
    override fun DrawScope.onDraw() {
        drawRect(color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------------------------

internal fun SemanticsNodeInteractionsProvider.tag(tag: String) =
    onNodeWithTag(tag, useUnmergedTree = true)

internal fun SemanticsNodeInteractionsProvider.inPanel(tag: String) =
    onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("stickerPanelSheet")), useUnmergedTree = true)

private fun SemanticsNodeInteractionsProvider.inRail(tag: String) =
    onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("stickerInlineRail")), useUnmergedTree = true)

private fun SemanticsNodeInteractionsProvider.inInlinePanel(tag: String) =
    onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("stickerInlinePanel")), useUnmergedTree = true)

private fun SemanticsNodeInteractionsProvider.describedAs(tag: String, description: String) =
    onNode(hasTestTag(tag) and hasContentDescription(description))

/** The tagged node's own text, or the text of a node inside it (button label vs tagged Text). */
private fun SemanticsNodeInteractionsProvider.textIn(tag: String, text: String) =
    onNode(
        hasText(text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

/** [textIn] scoped to the docked panel (C4): the rail stays composed underneath it. */
private fun SemanticsNodeInteractionsProvider.textInPanel(tag: String, text: String) =
    onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)) and
            hasAnyAncestor(hasTestTag("stickerPanelSheet")),
        useUnmergedTree = true,
    )

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.awaitPanel() = waitUntil(timeoutMillis = 5_000) {
    onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitNoPanel() = waitUntil(timeoutMillis = 5_000) {
    onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
}

/** How far a scrollable node has scrolled — survives lazy items being disposed. */
private fun SemanticsNodeInteraction.verticalScrollOffset(): Float =
    fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

private fun SemanticsNodeInteraction.hasSelectedProperty(): Boolean =
    fetchSemanticsNode().config.contains(SemanticsProperties.Selected)

private fun SemanticsNodeInteraction.role(): Role? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)

/** The rail's scrolling viewport (the `LazyRow` node), not the tagged outer container. */
internal fun SemanticsNodeInteractionsProvider.railViewport() =
    onNode(
        hasScrollAction() and hasAnyAncestor(hasTestTag("stickerInlineRail")),
        useUnmergedTree = true,
    )

/** Rail item count: `None` + `Import` + user stickers + the 12 built-ins. */
internal fun railItemCount(userStickers: Int) = 2 + userStickers + BuiltInSticker.entries.size

/** Asserts the tagged nodes sit in this left-to-right order. */
private fun SemanticsNodeInteractionsProvider.assertLeftToRight(vararg tags: String) {
    val lefts = tags.map { it to tag(it).getUnclippedBoundsInRoot().left }
    lefts.zipWithNext { (leftTag, left), (rightTag, right) ->
        assertTrue(
            left < right,
            "$leftTag (${left.value}dp) must sit left of $rightTag (${right.value}dp)",
        )
    }
}

/** C1 fixed overlay height: `max(bottomChromeHeight, min(0.48 × rootHeight, 440.dp))`. */
private fun expectedOverlayHeight(rootHeight: Dp, bottomChromeHeight: Dp): Dp =
    maxOf(bottomChromeHeight, minOf(rootHeight * 0.48f, 440.dp))

@OptIn(ExperimentalTestApi::class)
class StickerPanelSheetUiTest {

    // -----------------------------------------------------------------------------------------
    // C2 — built-in catalog and sticker bitmap
    // -----------------------------------------------------------------------------------------

    @Test
    fun builtInCatalogFollowsContractOrderAndBothFileNameForms() {
        assertEquals(
            listOf(
                "verified",
                "original",
                "no_ai",
                "no_repost",
                "do_not_copy",
                "rights_reserved",
                "sample",
                "private",
                "smiley",
                "seal_original",
                "no_reprint",
                "anti_theft",
            ),
            BuiltInSticker.entries.map { it.key },
        )
        // One distinct drawable and one distinct label per entry — no copy-paste collisions.
        assertEquals(12, BuiltInSticker.entries.map { it.drawable }.distinct().size)
        assertEquals(12, BuiltInSticker.entries.map { it.label }.distinct().size)

        BuiltInSticker.entries.forEach { builtIn ->
            assertEquals(
                builtIn,
                BuiltInSticker.fromIcon(MediaRef("/tmp/stickers/icon-builtin-${builtIn.key}.png")),
            )
            assertEquals(
                builtIn,
                BuiltInSticker.fromIcon(MediaRef("C:\\Users\\rosu\\AppData\\icon_builtin_${builtIn.key}.png")),
            )
            assertTrue(builtIn.matches(MediaRef("C:\\mixed/dir\\icon-builtin-${builtIn.key}.png")))
        }
        assertNull(BuiltInSticker.fromIcon(MediaRef("C:\\Users\\rosu\\icon-user-1.png")))
        // `copyright` was renamed to `rights_reserved` on purpose (C2) — stale files stop matching.
        assertNull(BuiltInSticker.fromIcon(MediaRef("/tmp/icon-builtin-copyright.png")))
        assertNull(BuiltInSticker.fromIcon(MediaRef.Empty))
    }

    @Test
    fun stickerBitmapKeepsAspectRatioAtIconMaxEdgeAndAppliesNoTint() {
        assertEquals(256, WatermarkIconCache.ICON_MAX_EDGE_PX)

        val wide = SolidPainter(Size(120f, 60f), Color.Red).toStickerBitmap()
        assertEquals(WatermarkIconCache.ICON_MAX_EDGE_PX, wide.width)
        assertEquals(WatermarkIconCache.ICON_MAX_EDGE_PX / 2, wide.height)

        val tall = SolidPainter(Size(60f, 120f), Color.Red).toStickerBitmap()
        assertEquals(WatermarkIconCache.ICON_MAX_EDGE_PX / 2, tall.width)
        assertEquals(WatermarkIconCache.ICON_MAX_EDGE_PX, tall.height)

        val square = SolidPainter(Size(10f, 10f), Color.Red).toStickerBitmap()
        assertEquals(WatermarkIconCache.ICON_MAX_EDGE_PX, square.width)
        assertEquals(WatermarkIconCache.ICON_MAX_EDGE_PX, square.height)

        val bounded = SolidPainter(Size(120f, 60f), Color.Red).toStickerBitmap(64)
        assertEquals(64, bounded.width)
        assertEquals(32, bounded.height)

        // No tint: a red painter stays red (the pre-v3 path forced every mark to white).
        val center = wide.toPixelMap()[wide.width / 2, wide.height / 2]
        assertTrue(
            center.red > 0.9f && center.green < 0.1f && center.blue < 0.1f && center.alpha > 0.9f,
            "toStickerBitmap must not tint the painter, got $center",
        )
    }

    // -----------------------------------------------------------------------------------------
    // 1.1 — rail
    // -----------------------------------------------------------------------------------------

    @Test
    fun railShowsNoneImportAppliedBuiltInFirstAndPinsAllAtTheEnd() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val userTwo = Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png"))
        val harness = StickerHarness(
            icon = MediaRef("/tmp/icon-builtin-sample.png"),
            stickers = listOf(userOne, userTwo),
        )
        lateinit var noneLabel: String
        lateinit var importLabel: String
        lateinit var allLabel: String
        setContent {
            AppTheme {
                noneLabel = stringResource(Res.string.sticker_none)
                importLabel = stringResource(Res.string.sticker_import)
                allLabel = stringResource(Res.string.sticker_all_open)
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = false)
                }
            }
        }
        tag("stickerInlineRail").assertIsDisplayed()

        // [None] [Import] [applied built-in first among the stickers] [user stickers …] … [All]
        assertLeftToRight(
            "stickerClear",
            "stickerAdd",
            "stickerBuiltIn_sample",
            "stickerUser_user-1",
            "stickerUser_user-2",
        )
        assertLeftToRight("stickerUser_user-2", "stickerExpand")

        val rail = tag("stickerInlineRail").getUnclippedBoundsInRoot()
        val none = tag("stickerClear").getUnclippedBoundsInRoot()
        val importTile = tag("stickerAdd").getUnclippedBoundsInRoot()
        val all = tag("stickerExpand").getUnclippedBoundsInRoot()
        (none.left - rail.left).assertIsEqualTo(12.dp, "rail start padding", tolerance = 1.dp)
        (importTile.left - none.right).assertIsEqualTo(6.dp, "rail tile spacing", tolerance = 1.dp)
        (rail.right - all.right).assertIsEqualTo(12.dp, "All inset from the end", tolerance = 1.dp)

        tag("stickerClear").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        tag("stickerBuiltIn_sample").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        tag("stickerExpand").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)

        // Content descriptions (C4).
        describedAs("stickerClear", noneLabel).assertExists()
        describedAs("stickerAdd", importLabel).assertExists()
        describedAs("stickerExpand", allLabel).assertExists()

        // The applied built-in owns the single-choice selection; None does not.
        tag("stickerBuiltIn_sample").assertIsSelected()
        tag("stickerClear").assertIsNotSelected()
        tag("stickerUser_user-1").assertIsNotSelected()

        // Retired chrome: no selected corner badge, no inline title, no rail dividers' `×`.
        tag("stickerSelectedBadge").assertDoesNotExist()
        tag("stickerInlineTitle").assertDoesNotExist()
        tag("stickerPanelSheet").assertDoesNotExist()
    }

    @Test
    fun railNoneIsSelectedWithoutIconAndStaysPresentAfterClearing() = runComposeUiTest {
        val harness = StickerHarness()
        setContent {
            AppTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = false)
                }
            }
        }
        // No icon applied -> None is the selected option of the group.
        tag("stickerClear").assertIsDisplayed().assertIsSelected()

        tag("stickerBuiltIn_verified").performClick()
        runOnIdle { assertEquals(listOf(BuiltInSticker.Verified), harness.appliedBuiltIns) }
        tag("stickerBuiltIn_verified").assertIsSelected()
        tag("stickerClear").assertIsNotSelected()

        // Tapping None clears and takes the selection back — the tile never disappears.
        tag("stickerClear").performClick()
        runOnIdle { assertEquals(1, harness.clears) }
        tag("stickerClear").assertIsDisplayed().assertIsSelected()
        tag("stickerBuiltIn_verified").assertIsNotSelected()
        tag("stickerInlineRail").assertIsDisplayed()
    }

    @Test
    fun railImportIsWideOnlyWhileThereAreNoUserStickers() = runComposeUiTest {
        val harness = StickerHarness()
        lateinit var importLabel: String
        setContent {
            AppTheme {
                importLabel = stringResource(Res.string.sticker_import)
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = false)
                }
            }
        }
        // 136.dp is the wide tile's floor, not its width: it grows with its label up to 220.dp so
        // a longer locale ellipsises as late as possible.
        val wide = tag("stickerAdd")
            .assertHeightIsEqualTo(56.dp)
            .assertWidthIsAtLeast(136.dp)
            .getUnclippedBoundsInRoot()
        assertTrue(
            (wide.right - wide.left) <= 220.dp,
            "wide Import must stay under its cap, got ${(wide.right - wide.left).value}dp",
        )
        textIn("stickerAdd", importLabel).assertExists()
        tag("stickerAdd").performClick()
        runOnIdle { assertEquals(1, harness.adds) }

        runOnIdle {
            harness.stickers = listOf(Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png")))
        }
        waitForIdle()
        tag("stickerAdd").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        textIn("stickerAdd", importLabel).assertDoesNotExist()
    }

    @Test
    fun railTapNeverReordersTilesUnderTheFinger() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(
            icon = MediaRef("/tmp/icon-builtin-sample.png"),
            stickers = listOf(userOne),
        )
        setContent {
            AppTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = false)
                }
            }
        }
        val tiles = listOf(
            "stickerClear",
            "stickerAdd",
            "stickerBuiltIn_sample",
            "stickerUser_user-1",
            "stickerBuiltIn_verified",
        )
        val before = tiles.associateWith { tag(it).getUnclippedBoundsInRoot().left }

        // Applying the user sticker drops the hoisted built-in out of "applied" — the rail order
        // is recomputed on composition / panel close / list change, never on a tap.
        tag("stickerUser_user-1").performClick()
        runOnIdle { assertEquals(listOf(userOne), harness.appliedUsers) }
        waitForIdle()
        tag("stickerUser_user-1").assertIsSelected()
        tag("stickerBuiltIn_sample").assertIsNotSelected()
        before.forEach { (tileTag, left) ->
            tag(tileTag).getUnclippedBoundsInRoot().left
                .assertIsEqualTo(left, "$tileTag must not move on tap", tolerance = 1.dp)
        }

        // Re-applying a built-in keeps the same geometry too.
        tag("stickerBuiltIn_sample").performClick()
        runOnIdle { assertEquals(listOf(BuiltInSticker.Sample), harness.appliedBuiltIns) }
        waitForIdle()
        before.forEach { (tileTag, left) ->
            tag(tileTag).getUnclippedBoundsInRoot().left
                .assertIsEqualTo(left, "$tileTag must not move on tap", tolerance = 1.dp)
        }
    }

    @Test
    fun railAllTileOpensTheDockedPanelOverTheLiveCanvas() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val harness = StickerHarness(icon = MediaRef("/tmp/icon-builtin-verified.png"))
        var canvasClicks = 0
        setContent {
            AppTheme {
                DockedStickerScaffold(
                    host = host,
                    harness = harness,
                    onCanvasClick = { canvasClicks += 1 },
                )
            }
        }
        tag("stickerPanelSheet").assertDoesNotExist()
        tag("stickerExpand").performClick()
        awaitPanel()
        tag("stickerPanelSheet").assertIsDisplayed()

        // Non-modal, no scrim: the canvas above keeps receiving real touch while the panel is open.
        tag("testLiveCanvas").performTouchInput { click(Offset(centerX, 16.dp.toPx())) }
        runOnIdle { assertEquals(1, canvasClicks) }
        tag("stickerPanelSheet").assertIsDisplayed()

        // A short drag on the handle springs back; past 48.dp it dismisses.
        tag("stickerSheetDragHandle").performTouchInput {
            down(center)
            moveBy(Offset(0f, 16.dp.toPx()))
            moveBy(Offset(0f, 16.dp.toPx()))
            up()
        }
        tag("stickerPanelSheet").assertIsDisplayed()
        tag("stickerSheetDragHandle").performTouchInput {
            down(center)
            moveBy(Offset(0f, 24.dp.toPx()))
            moveBy(Offset(0f, 64.dp.toPx()))
            up()
        }
        awaitNoPanel()
    }

    @Test
    fun railLongPressOpensPanelInSelectionModeWithThatStickerPicked() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val userTwo = Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png"))
        val harness = StickerHarness(stickers = listOf(userOne, userTwo))
        lateinit var selectedOne: String
        lateinit var deleteOne: String
        lateinit var deleteTitle: String
        lateinit var confirmLabel: String
        setContent {
            AppTheme {
                selectedOne = stringResource(Res.string.sticker_selected_count, 1)
                deleteOne = stringResource(Res.string.sticker_delete_selected, 1)
                deleteTitle = stringResource(Res.string.sticker_delete_title)
                confirmLabel = stringResource(Res.string.template_delete)
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        inRail("stickerUser_user-2").performTouchInput { longClick() }
        awaitPanel()

        textIn("stickerSelectedCount", selectedOne).assertIsDisplayed()
        inPanel("stickerManageBatchBar").assertIsDisplayed()
        textIn("stickerManageDeleteSelected", deleteOne).assertIsDisplayed()

        // Deleting proves *which* sticker the long press picked.
        inPanel("stickerManageDeleteSelected").performClick()
        onNodeWithText(deleteTitle, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmLabel, useUnmergedTree = true).performClick()
        runOnIdle {
            assertEquals(listOf(listOf(userTwo)), harness.deletedBatches)
            assertEquals(listOf(userOne), harness.stickers)
        }
    }

    /**
     * Rail end region: the list viewport stops before the pinned `All` tile, the 26.dp fade sits
     * over the end of that viewport (paint only), and the strip beyond `All` belongs to nobody.
     */
    @Test
    fun railEndRegionKeepsTheListOffTheAllStripAndClearOfTheFade() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        setContent {
            AppTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = false)
                }
            }
        }
        val rail = tag("stickerInlineRail").getUnclippedBoundsInRoot()
        tag("stickerExpand").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        val all = tag("stickerExpand").getUnclippedBoundsInRoot()
        (rail.right - all.right).assertIsEqualTo(12.dp, "All inset from the rail end", tolerance = 1.dp)

        // The viewport itself ends 68.dp before the rail: no list content under the All tile.
        val viewport = railViewport().getUnclippedBoundsInRoot()
        viewport.left.assertIsEqualTo(rail.left, "rail viewport start", tolerance = 1.dp)
        (rail.right - viewport.right).assertIsEqualTo(
            68.dp,
            "rail viewport must end before the pinned All tile",
            tolerance = 1.dp,
        )

        // At maximum scroll the last tile ends clear of the 26.dp fade (68 + 26 before the end).
        railViewport().performScrollToIndex(railItemCount(1) - 1)
        waitForIdle()
        val lastTag = "stickerBuiltIn_${BuiltInSticker.entries.last().key}"
        val lastTile = tag(lastTag).getUnclippedBoundsInRoot()
        (rail.right - lastTile.right).assertIsEqualTo(
            94.dp,
            "last tile at maximum scroll must clear the fade",
            tolerance = 1.dp,
        )
        assertTrue(
            lastTile.right <= all.left,
            "last tile (${lastTile.right.value}dp) must stay left of All (${all.left.value}dp)",
        )

        // Mid-scroll: the 12.dp strip beyond All reaches nothing at all.
        railViewport().performScrollToIndex(railItemCount(1) / 2)
        waitForIdle()
        tag("stickerInlineRail").performTouchInput {
            click(Offset(width - 3.dp.toPx(), height / 2f))
            click(Offset(width - 9.dp.toPx(), height / 2f))
        }
        waitForIdle()
        runOnIdle {
            assertTrue(
                harness.appliedBuiltIns.isEmpty() && harness.appliedUsers.isEmpty(),
                "a tap beyond All must apply nothing, got ${harness.appliedBuiltIns} / ${harness.appliedUsers}",
            )
            assertEquals(0, harness.clears)
            assertEquals(0, harness.adds)
        }
        tag("stickerPanelSheet").assertDoesNotExist()

        // The fade is paint only: the half-visible tile under it still takes the tap.
        val fadeCenter = viewport.right - rail.left - 13.dp
        tag("stickerInlineRail").performTouchInput {
            click(Offset(fadeCenter.toPx(), height / 2f))
        }
        waitForIdle()
        runOnIdle {
            assertTrue(
                harness.appliedBuiltIns.isNotEmpty() || harness.appliedUsers.isNotEmpty(),
                "the fade must not swallow the tap on the tile under it",
            )
        }
    }

    @Test
    fun railEndRegionMirrorsInRtl() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(modifier = Modifier.width(360.dp)) {
                        StickerPanelHost(harness = harness, useLargeDialog = false)
                    }
                }
            }
        }
        val rail = tag("stickerInlineRail").getUnclippedBoundsInRoot()
        val all = tag("stickerExpand").getUnclippedBoundsInRoot()
        tag("stickerExpand").assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        (all.left - rail.left).assertIsEqualTo(
            12.dp,
            "RTL pins All at the left with its 12.dp outer margin",
            tolerance = 1.dp,
        )
        // Tiles start at the right: None is the right-most tile.
        assertTrue(
            tag("stickerClear").getUnclippedBoundsInRoot().right > all.right,
            "RTL must mirror the rail: None sits right of All",
        )

        val viewport = railViewport().getUnclippedBoundsInRoot()
        viewport.right.assertIsEqualTo(rail.right, "RTL rail viewport start", tolerance = 1.dp)
        (viewport.left - rail.left).assertIsEqualTo(
            68.dp,
            "RTL rail viewport must end before the pinned All tile",
            tolerance = 1.dp,
        )

        railViewport().performScrollToIndex(railItemCount(1) - 1)
        waitForIdle()
        val lastTile = tag("stickerBuiltIn_${BuiltInSticker.entries.last().key}")
            .getUnclippedBoundsInRoot()
        (lastTile.left - rail.left).assertIsEqualTo(
            94.dp,
            "RTL last tile at maximum scroll must clear the fade",
            tolerance = 1.dp,
        )
        assertTrue(
            lastTile.left >= all.right,
            "RTL last tile (${lastTile.left.value}dp) must stay right of All (${all.right.value}dp)",
        )

        railViewport().performScrollToIndex(railItemCount(1) / 2)
        waitForIdle()
        tag("stickerInlineRail").performTouchInput {
            click(Offset(3.dp.toPx(), height / 2f))
            click(Offset(9.dp.toPx(), height / 2f))
        }
        waitForIdle()
        runOnIdle {
            assertTrue(
                harness.appliedBuiltIns.isEmpty() && harness.appliedUsers.isEmpty(),
                "RTL: a tap beyond All must apply nothing, got ${harness.appliedBuiltIns} / ${harness.appliedUsers}",
            )
            assertEquals(0, harness.clears)
            assertEquals(0, harness.adds)
        }
        tag("stickerPanelSheet").assertDoesNotExist()
    }

    // -----------------------------------------------------------------------------------------
    // 1.2 — panel
    // -----------------------------------------------------------------------------------------

    @Test
    fun panelHeaderIsTitlePlusCloseOnlyAndNoneLivesInTheGrid() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        lateinit var title: String
        lateinit var noneLabel: String
        lateinit var manageLabel: String
        setContent {
            AppTheme {
                title = stringResource(Res.string.water_mark_mode_image)
                noneLabel = stringResource(Res.string.sticker_none)
                manageLabel = stringResource(Res.string.sticker_manage)
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()

        textIn("stickerPanelSheet", title).assertIsDisplayed()
        tag("stickerSheetClose").assertIsDisplayed()
        tag("stickerSheetDragHandle").assertIsDisplayed()

        // No header Remove action: the only clear affordance is the None tile, inside the grid,
        // present even though nothing is applied — and it owns the selection in that state.
        val close = tag("stickerSheetClose").getUnclippedBoundsInRoot()
        val sheetNone = tag("stickerSheetClear").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(
            sheetNone.top >= close.bottom,
            "None must sit in the grid below the header, got ${sheetNone.top.value}dp vs ${close.bottom.value}dp",
        )
        tag("stickerSheetClear").assertIsSelected()
        describedAs("stickerSheetClear", noneLabel).assertExists()
        tag("stickerSelectedBadge").assertDoesNotExist()
        tag("stickerInlineTitle").assertDoesNotExist()

        // Mine label row hosts Manage; Built-in label row follows.
        inPanel("stickerSectionMine").assertIsDisplayed()
        inPanel("stickerSectionBuiltIn").assertIsDisplayed()
        textIn("stickerManageToggle", manageLabel).assertExists()
        inPanel("stickerManageToggle")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(40.dp)
        val mineLabel = inPanel("stickerSectionMine").getUnclippedBoundsInRoot()
        val builtInLabel = inPanel("stickerSectionBuiltIn").getUnclippedBoundsInRoot()
        assertTrue(
            mineLabel.top < builtInLabel.top,
            "Mine section must precede Built-in, got ${mineLabel.top.value}dp vs ${builtInLabel.top.value}dp",
        )

        // Closing the panel leaves the rail intact.
        tag("stickerSheetClose").performClick()
        awaitNoPanel()
        tag("stickerInlineRail").assertIsDisplayed()
    }

    @Test
    fun panelWithoutUserStickersKeepsBuiltInSectionAndSpansImportAcrossTheRestOfTheRow() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val harness = StickerHarness()
        lateinit var importLabel: String
        setContent {
            AppTheme {
                importLabel = stringResource(Res.string.sticker_import)
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()

        inPanel("stickerSectionMine").assertDoesNotExist()
        inPanel("stickerSectionBuiltIn").assertIsDisplayed()
        inPanel("stickerManageToggle").assertDoesNotExist()
        inPanel("stickerManageBatchBar").assertDoesNotExist()

        // Import takes the rest of the first row — columns 2…4, no hole — and shows its label.
        val none = tag("stickerSheetClear").getUnclippedBoundsInRoot()
        val importTile = inPanel("stickerAdd").getUnclippedBoundsInRoot()
        val cell = none.right - none.left
        (importTile.right - importTile.left).assertIsEqualTo(
            cell * 3f + 4.dp * 2f,
            "Import spans the rest of the row (3 of 4 columns)",
            tolerance = 1.5.dp,
        )
        // One cell high, like every other tile in its row.
        (importTile.bottom - importTile.top).assertIsEqualTo(
            none.bottom - none.top,
            "wide Import stays exactly one cell high",
            tolerance = 1.5.dp,
        )
        // Scoped to the panel: with no user stickers the rail below shows a wide Import too.
        textInPanel("stickerAdd", importLabel).assertExists()
    }

    @Test
    fun panelApplyKeepsThePanelOpenAndMovesTheSelection() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val harness = StickerHarness()
        setContent {
            AppTheme {
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()
        tag("stickerSheetClear").assertIsSelected()

        inPanel("stickerBuiltIn_verified").performClick()
        runOnIdle { assertEquals(listOf(BuiltInSticker.Verified), harness.appliedBuiltIns) }
        waitForIdle()
        // dismissOnSelect = false: the panel stays up for live comparison.
        tag("stickerPanelSheet").assertIsDisplayed()
        inPanel("stickerBuiltIn_verified").assertIsSelected()
        tag("stickerSheetClear").assertIsNotSelected()

        inPanel("stickerBuiltIn_original").performClick()
        runOnIdle {
            assertEquals(
                listOf(BuiltInSticker.Verified, BuiltInSticker.Original),
                harness.appliedBuiltIns,
            )
        }
        waitForIdle()
        tag("stickerPanelSheet").assertIsDisplayed()
        inPanel("stickerBuiltIn_original").assertIsSelected()
        inPanel("stickerBuiltIn_verified").assertIsNotSelected()
    }

    // -----------------------------------------------------------------------------------------
    // 1.3 — selection mode
    // -----------------------------------------------------------------------------------------

    @Test
    fun selectionModeIgnoresNoneImportAndBuiltInsAndDoneLeavesIt() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(
            icon = MediaRef("/tmp/icon-builtin-verified.png"),
            stickers = listOf(userOne),
        )
        lateinit var selectedZero: String
        lateinit var deleteZero: String
        lateinit var selectAll: String
        lateinit var doneLabel: String
        lateinit var deleteTitle: String
        setContent {
            AppTheme {
                selectedZero = stringResource(Res.string.sticker_selected_count, 0)
                deleteZero = stringResource(Res.string.sticker_delete_selected, 0)
                selectAll = stringResource(Res.string.sticker_select_all)
                doneLabel = stringResource(Res.string.preview_selection_done)
                deleteTitle = stringResource(Res.string.sticker_delete_title)
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()
        inPanel("stickerManageToggle").performClick()
        waitForIdle()

        // The header row is replaced in place by count / select-all / done.
        textIn("stickerSelectedCount", selectedZero).assertIsDisplayed()
        textIn("stickerManageSelectAll", selectAll).assertIsDisplayed()
        textIn("stickerManageDone", doneLabel).assertIsDisplayed()
        inPanel("stickerManageToggle").assertDoesNotExist()

        // One full-width 48.dp delete button, inert while nothing is picked.
        val bar = inPanel("stickerManageBatchBar").assertIsDisplayed().getUnclippedBoundsInRoot()
        val deleteButton = inPanel("stickerManageDeleteSelected").assertIsDisplayed()
        deleteButton.assertHeightIsEqualTo(48.dp)
        deleteButton.assertIsNotEnabled()
        val deleteBounds = deleteButton.getUnclippedBoundsInRoot()
        assertTrue(
            (deleteBounds.right - deleteBounds.left) >= (bar.right - bar.left) - 32.dp,
            "delete button must be full width, got ${(deleteBounds.right - deleteBounds.left).value}dp " +
                "in a ${(bar.right - bar.left).value}dp bar",
        )
        textIn("stickerManageDeleteSelected", deleteZero).assertIsDisplayed()
        deleteButton.performClick()
        waitForIdle()
        onNodeWithText(deleteTitle, useUnmergedTree = true).assertDoesNotExist()

        // None, Import and the built-ins stay put but ignore input.
        tag("stickerSheetClear").assertIsDisplayed().performClick()
        inPanel("stickerAdd").assertIsDisplayed().performClick()
        inPanel("stickerBuiltIn_verified").assertIsDisplayed().performClick()
        inPanel("stickerBuiltIn_original").performClick()
        waitForIdle()
        runOnIdle {
            assertEquals(0, harness.clears)
            assertEquals(0, harness.adds)
            assertTrue(harness.appliedBuiltIns.isEmpty(), "built-ins must ignore input in selection mode")
        }
        textIn("stickerSelectedCount", selectedZero).assertIsDisplayed()

        // No applied highlight is drawn in this mode, but `selected` semantics keep reporting what
        // is applied (spec 1.3).
        inPanel("stickerBuiltIn_verified").assertIsSelected()
        tag("stickerSheetClear").assertIsNotSelected()

        // Done leaves selection mode and the tiles go live again.
        inPanel("stickerManageDone").performClick()
        waitForIdle()
        tag("stickerSelectedCount").assertDoesNotExist()
        inPanel("stickerManageBatchBar").assertDoesNotExist()
        inPanel("stickerManageSelect_user-1").assertDoesNotExist()
        inPanel("stickerManageToggle").assertIsDisplayed()
        inPanel("stickerBuiltIn_original").performClick()
        runOnIdle { assertEquals(listOf(BuiltInSticker.Original), harness.appliedBuiltIns) }
    }

    @Test
    fun selectionModeSelectsAllDeletesWithOneConfirmAndEndsWhenTheListEmpties() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val userTwo = Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png"))
        val harness = StickerHarness(stickers = listOf(userOne, userTwo))
        lateinit var selectedZero: String
        lateinit var selectedOne: String
        lateinit var selectedTwo: String
        lateinit var selectAll: String
        lateinit var deselectAll: String
        lateinit var deleteTwo: String
        lateinit var deleteTitle: String
        lateinit var confirmTwo: String
        lateinit var confirmLabel: String
        lateinit var cancelLabel: String
        setContent {
            AppTheme {
                selectedZero = stringResource(Res.string.sticker_selected_count, 0)
                selectedOne = stringResource(Res.string.sticker_selected_count, 1)
                selectedTwo = stringResource(Res.string.sticker_selected_count, 2)
                selectAll = stringResource(Res.string.sticker_select_all)
                deselectAll = stringResource(Res.string.sticker_deselect_all)
                deleteTwo = stringResource(Res.string.sticker_delete_selected, 2)
                deleteTitle = stringResource(Res.string.sticker_delete_title)
                confirmTwo = stringResource(Res.string.sticker_delete_batch_confirm, 2)
                confirmLabel = stringResource(Res.string.template_delete)
                cancelLabel = stringResource(Res.string.tips_cancel_dialog)
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()
        inPanel("stickerManageToggle").performClick()
        waitForIdle()

        // Checkbox toggles one sticker on and off again.
        inPanel("stickerManageSelect_user-1").assertIsDisplayed().performClick()
        textIn("stickerSelectedCount", selectedOne).assertIsDisplayed()
        inPanel("stickerManageSelect_user-1").performClick()
        textIn("stickerSelectedCount", selectedZero).assertIsDisplayed()

        // Select all / deselect all flips in place.
        inPanel("stickerManageSelectAll").performClick()
        textIn("stickerSelectedCount", selectedTwo).assertIsDisplayed()
        textIn("stickerManageSelectAll", deselectAll).assertIsDisplayed()
        inPanel("stickerManageSelectAll").performClick()
        textIn("stickerManageSelectAll", selectAll).assertIsDisplayed()
        inPanel("stickerManageSelectAll").performClick()
        textIn("stickerManageDeleteSelected", deleteTwo).assertIsDisplayed()

        // Asks once, and a dismissed dialog deletes nothing.
        inPanel("stickerManageDeleteSelected").performClick()
        onNodeWithText(deleteTitle, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmTwo, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(cancelLabel, useUnmergedTree = true).assertIsDisplayed().performClick()
        waitForIdle()
        runOnIdle {
            assertTrue(harness.deletedBatches.isEmpty(), "cancel must not delete")
            assertEquals(listOf(userOne, userTwo), harness.stickers)
        }
        textIn("stickerSelectedCount", selectedTwo).assertIsDisplayed()

        inPanel("stickerManageDeleteSelected").performClick()
        onNodeWithText(confirmLabel, useUnmergedTree = true).performClick()
        runOnIdle {
            assertEquals(listOf(listOf(userOne, userTwo)), harness.deletedBatches)
            assertTrue(harness.stickers.isEmpty())
        }
        waitForIdle()

        // Empty list -> selection mode ends, Mine row goes away, Built-in row stays.
        tag("stickerSelectedCount").assertDoesNotExist()
        inPanel("stickerManageBatchBar").assertDoesNotExist()
        inPanel("stickerSectionMine").assertDoesNotExist()
        inPanel("stickerSectionBuiltIn").assertIsDisplayed()
        tag("stickerSheetClear").assertIsDisplayed()
        tag("stickerPanelSheet").assertIsDisplayed()
    }

    @Test
    fun closingThePanelLeavesSelectionMode() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        setContent {
            AppTheme {
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()
        inPanel("stickerManageToggle").performClick()
        waitForIdle()
        inPanel("stickerManageBatchBar").assertIsDisplayed()

        // Selection mode owns the header row, so close through the handle drag (>= 48.dp).
        tag("stickerSheetDragHandle").performTouchInput {
            down(center)
            moveBy(Offset(0f, 24.dp.toPx()))
            moveBy(Offset(0f, 64.dp.toPx()))
            up()
        }
        awaitNoPanel()

        tag("stickerExpand").performClick()
        awaitPanel()
        tag("stickerSheetClose").assertIsDisplayed()
        tag("stickerSelectedCount").assertDoesNotExist()
        inPanel("stickerManageBatchBar").assertDoesNotExist()
        inPanel("stickerManageSelect_user-1").assertDoesNotExist()
        inPanel("stickerManageToggle").assertIsDisplayed()
    }

    @Test
    fun dockedSelectionHeaderKeepsDoneUsableAtFontScale15() =
        assertDockedSelectionHeaderKeepsDone(1.5f)

    @Test
    fun dockedSelectionHeaderKeepsDoneUsableAtFontScale20() =
        assertDockedSelectionHeaderKeepsDone(2f)

    /**
     * Width pressure on the selection header (320 dp phone, large font scale, the longer
     * "Deselect all" label): Done keeps its full width, select-all gives way next, and the count
     * gives way first — one line each, same header height.
     */
    private fun assertDockedSelectionHeaderKeepsDone(fontScale: Float) = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val users = listOf(
            Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png")),
            Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png")),
        )
        val harness = StickerHarness(stickers = users)
        lateinit var deselectAll: String
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    deselectAll = stringResource(Res.string.sticker_deselect_all)
                    Column {
                        // What the Done label measures when nothing squeezes it (14 sp header form).
                        Box(modifier = Modifier.requiredWidth(400.dp)) {
                            Text(
                                text = stringResource(Res.string.preview_selection_done),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.testTag("testDoneLabelReference"),
                            )
                        }
                        DockedStickerScaffold(host = host, harness = harness, width = 320.dp)
                    }
                }
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()
        inPanel("stickerManageToggle").performClick()
        waitForIdle()
        inPanel("stickerManageSelectAll").performClick()
        waitForIdle()
        textInPanel("stickerManageSelectAll", deselectAll).assertExists()

        val panel = tag("stickerPanelSheet").getUnclippedBoundsInRoot()
        val done = inPanel("stickerManageDone").assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val selectAll = inPanel("stickerManageSelectAll").getUnclippedBoundsInRoot()
        val count = tag("stickerSelectedCount").getUnclippedBoundsInRoot()
        val reference = tag("testDoneLabelReference").getUnclippedBoundsInRoot()

        assertTrue(
            done.left >= panel.left && done.right <= panel.right,
            "Done (${done.left.value}..${done.right.value}dp) must stay inside the header " +
                "(${panel.left.value}..${panel.right.value}dp) at font scale $fontScale",
        )
        assertTrue(
            selectAll.right <= done.left + 0.5.dp,
            "select-all (${selectAll.right.value}dp) must not overlap Done (${done.left.value}dp)",
        )
        assertTrue(
            count.right <= selectAll.left + 0.5.dp,
            "the count (${count.right.value}dp) must give way to select-all (${selectAll.left.value}dp)",
        )
        // Not clipped: Done still gets its label's full width plus its 12.dp side padding.
        assertTrue(
            (done.right - done.left) >= (reference.right - reference.left) + 24.dp - 1.dp,
            "Done (${(done.right - done.left).value}dp) must fit its label " +
                "(${(reference.right - reference.left).value}dp + 24dp padding)",
        )
        // One line, unchanged height.
        inPanel("stickerManageDone").assertHeightIsEqualTo(44.dp)
        assertTrue(
            (count.bottom - count.top) <= 44.dp,
            "the count must stay on one line, got ${(count.bottom - count.top).value}dp",
        )

        inPanel("stickerManageDone").performClick()
        waitForIdle()
        tag("stickerSelectedCount").assertDoesNotExist()
        inPanel("stickerManageToggle").assertIsDisplayed()
    }

    @Test
    fun enteringSelectionModeInTheDockedPanelMovesNoTile() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        setContent {
            AppTheme {
                DockedStickerScaffold(host = host, harness = harness)
            }
        }
        tag("stickerExpand").performClick()
        awaitPanel()
        val before = inPanel("stickerUser_user-1").getUnclippedBoundsInRoot().top

        inPanel("stickerManageToggle").performClick()
        waitForIdle()
        inPanel("stickerUser_user-1").getUnclippedBoundsInRoot().top.assertIsEqualTo(
            before,
            "Manage must not move the grid",
            tolerance = 0.5.dp,
        )

        inPanel("stickerManageDone").performClick()
        waitForIdle()
        inPanel("stickerUser_user-1").getUnclippedBoundsInRoot().top.assertIsEqualTo(
            before,
            "Done must not move the grid",
            tolerance = 0.5.dp,
        )
    }

    // -----------------------------------------------------------------------------------------
    // 1.4 — inspector (expanded / wide)
    // -----------------------------------------------------------------------------------------

    @Test
    fun inspectorEmbedsTheBodyWithoutTitleRowOrRemoveAction() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        setContent {
            AppTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = true)
                }
            }
        }
        tag("stickerInlinePanel").assertIsDisplayed()
        tag("stickerInlineTitle").assertDoesNotExist()
        tag("stickerSelectedBadge").assertDoesNotExist()
        tag("stickerExpand").assertDoesNotExist()
        tag("stickerPanelSheet").assertDoesNotExist()
        // Corner delete only shows under pointer hover.
        tag("stickerDelete_user-1").assertDoesNotExist()

        tag("stickerSectionMine").assertIsDisplayed()
        tag("stickerSectionBuiltIn").assertIsDisplayed()

        // None is always present and holds the selection while nothing is applied.
        tag("stickerClear").assertIsDisplayed().assertIsSelected()
        tag("stickerBuiltIn_verified").performClick()
        runOnIdle { assertEquals(listOf(BuiltInSticker.Verified), harness.appliedBuiltIns) }
        waitForIdle()
        tag("stickerInlinePanel").assertIsDisplayed()
        tag("stickerBuiltIn_verified").assertIsSelected()
        tag("stickerClear").assertIsNotSelected()

        tag("stickerClear").performClick()
        runOnIdle { assertEquals(1, harness.clears) }
        tag("stickerClear").assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun inspectorLongPressEntersSelectionModeInPlaceWithDeleteUnderTheMineGrid() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val userTwo = Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png"))
        val harness = StickerHarness(icon = userOne.icon, stickers = listOf(userOne, userTwo))
        lateinit var selectedOne: String
        lateinit var deleteOne: String
        lateinit var deleteTitle: String
        lateinit var confirmOne: String
        lateinit var confirmLabel: String
        setContent {
            AppTheme {
                selectedOne = stringResource(Res.string.sticker_selected_count, 1)
                deleteOne = stringResource(Res.string.sticker_delete_selected, 1)
                deleteTitle = stringResource(Res.string.sticker_delete_title)
                confirmOne = stringResource(Res.string.sticker_delete_batch_confirm, 1)
                confirmLabel = stringResource(Res.string.template_delete)
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = true)
                }
            }
        }
        tag("stickerUser_user-2").performTouchInput { longClick() }
        waitForIdle()

        // The Mine label row becomes count / select all / done, in place.
        textIn("stickerSelectedCount", selectedOne).assertIsDisplayed()
        tag("stickerManageSelectAll").assertIsDisplayed()
        tag("stickerManageDone").assertIsDisplayed()
        tag("stickerSectionMine").assertDoesNotExist()
        textIn("stickerManageDeleteSelected", deleteOne).assertIsDisplayed()

        // The delete button sits between the Mine grid and the Built-in section.
        val mineTile = tag("stickerUser_user-2").getUnclippedBoundsInRoot()
        val builtInLabel = tag("stickerSectionBuiltIn").getUnclippedBoundsInRoot()
        val deleteButton = tag("stickerManageDeleteSelected").getUnclippedBoundsInRoot()
        assertTrue(
            deleteButton.top >= mineTile.bottom,
            "delete must sit under the Mine grid, got ${deleteButton.top.value}dp vs ${mineTile.bottom.value}dp",
        )
        assertTrue(
            deleteButton.bottom <= builtInLabel.top,
            "delete must sit above the Built-in section, got ${deleteButton.bottom.value}dp vs ${builtInLabel.top.value}dp",
        )

        tag("stickerManageDeleteSelected").performClick()
        onNodeWithText(deleteTitle, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmOne, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmLabel, useUnmergedTree = true).performClick()
        runOnIdle {
            assertEquals(listOf(listOf(userTwo)), harness.deletedBatches)
            assertEquals(listOf(userOne), harness.stickers)
        }
        waitForIdle()

        // Confirming a delete leaves selection mode (spec 1.3), even with a sticker still left:
        // the Mine label row and its Manage button come back.
        tag("stickerSelectedCount").assertDoesNotExist()
        tag("stickerManageBatchBar").assertDoesNotExist()
        tag("stickerManageSelect_user-1").assertDoesNotExist()
        tag("stickerSectionMine").assertIsDisplayed()
        tag("stickerManageToggle").assertIsDisplayed()

        // Back in via Manage: with an empty selection the one delete button is inert, and
        // emptying the list leaves selection mode too.
        tag("stickerManageToggle").performClick()
        waitForIdle()
        tag("stickerManageDeleteSelected").assertIsDisplayed().assertIsNotEnabled()
        tag("stickerManageSelectAll").assertIsDisplayed().performClick()
        tag("stickerManageDeleteSelected").performClick()
        onNodeWithText(confirmLabel, useUnmergedTree = true).performClick()
        runOnIdle { assertTrue(harness.stickers.isEmpty()) }
        waitForIdle()
        tag("stickerSelectedCount").assertDoesNotExist()
        tag("stickerManageBatchBar").assertDoesNotExist()
        tag("stickerSectionMine").assertDoesNotExist()
        tag("stickerSectionBuiltIn").assertIsDisplayed()
        tag("stickerClear").assertIsDisplayed()
    }

    /**
     * The inspector hands the panel a modifier that already carries `editorControl-Icon`, so both
     * tags only survive while `stickerInlinePanel` sits on a node of its own.
     */
    @Test
    fun expandedEditorKeepsTheInlinePanelTagReachableAndSpansImportAcrossTheRestOfTheRow() =
        runSkikoComposeUiTest(size = Size(1024f, 768f), density = Density(1f)) {
            val photo = ImageInfoUi(MediaRef("/tmp/photo.jpg"), width = 400, height = 300)
            val harness = StickerHarness()
            setContent {
                AppTheme {
                    EditorScreenUnderTest(
                        harness = harness,
                        photo = photo,
                        modifier = Modifier.width(900.dp).height(700.dp),
                        layoutClass = EditorLayoutClass.Expanded,
                    )
                }
            }
            waitForIdle()

            // Both tags survive because they now sit on two nodes: the option control the inspector
            // hands down, and the panel inside it.
            tag("editorControl-Icon").assertExists()
            tag("stickerInlinePanel").assertExists()
            onNode(
                hasTestTag("stickerInlinePanel") and
                    hasAnyAncestor(hasTestTag("editorControl-Icon")),
                useUnmergedTree = true,
            ).assertExists()
            // The tag is on the panel itself, not on some node beside the body.
            inInlinePanel("stickerSectionBuiltIn").assertExists()
            inInlinePanel("stickerBuiltIn_verified").assertExists()
            tag("stickerInlineRail").assertDoesNotExist()

            // Empty library: Import takes columns 2…4 of the first row, one cell high.
            val none = inInlinePanel("stickerClear").getUnclippedBoundsInRoot()
            val importTile = inInlinePanel("stickerAdd").getUnclippedBoundsInRoot()
            val cell = none.right - none.left
            (importTile.right - importTile.left).assertIsEqualTo(
                cell * 3f + 4.dp * 2f,
                "inspector Import spans the rest of the row (3 of 4 columns)",
                tolerance = 1.5.dp,
            )
            (importTile.bottom - importTile.top).assertIsEqualTo(
                none.bottom - none.top,
                "wide Import stays exactly one cell high",
                tolerance = 1.5.dp,
            )
        }

    /**
     * The inspector's inline selection row runs the same priority rule, at its narrowest realistic
     * width (under the 360 dp expanded inspector pane minus its padding) and font scale 2.
     */
    @Test
    fun inspectorSelectionRowKeepsDoneUsableAtItsNarrowestWidth() = runComposeUiTest {
        val users = listOf(
            Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png")),
            Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png")),
        )
        val harness = StickerHarness(stickers = users)
        lateinit var deselectAll: String
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    deselectAll = stringResource(Res.string.sticker_deselect_all)
                    Column {
                        // The compact (12 sp) Done label, unsqueezed.
                        Box(modifier = Modifier.requiredWidth(400.dp)) {
                            Text(
                                text = stringResource(Res.string.preview_selection_done),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.testTag("testDoneLabelReference"),
                            )
                        }
                        Box(modifier = Modifier.width(300.dp)) {
                            StickerPanelHost(harness = harness, useLargeDialog = true)
                        }
                    }
                }
            }
        }
        tag("stickerUser_user-2").performTouchInput { longClick() }
        waitForIdle()
        tag("stickerManageSelectAll").performClick()
        waitForIdle()
        textIn("stickerManageSelectAll", deselectAll).assertExists()

        val row = tag("stickerInlinePanel").getUnclippedBoundsInRoot()
        val done = tag("stickerManageDone").assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val selectAll = tag("stickerManageSelectAll").getUnclippedBoundsInRoot()
        val count = tag("stickerSelectedCount").getUnclippedBoundsInRoot()
        val reference = tag("testDoneLabelReference").getUnclippedBoundsInRoot()

        assertTrue(
            done.left >= row.left && done.right <= row.right,
            "Done (${done.left.value}..${done.right.value}dp) must stay inside the inspector row " +
                "(${row.left.value}..${row.right.value}dp)",
        )
        assertTrue(
            selectAll.right <= done.left + 0.5.dp,
            "select-all (${selectAll.right.value}dp) must not overlap Done (${done.left.value}dp)",
        )
        assertTrue(
            (done.right - done.left) >= (reference.right - reference.left) + 24.dp - 1.dp,
            "Done (${(done.right - done.left).value}dp) must fit its label " +
                "(${(reference.right - reference.left).value}dp + 24dp padding)",
        )
        tag("stickerManageDone").assertHeightIsEqualTo(40.dp)
        assertTrue(
            (count.bottom - count.top) <= 40.dp,
            "the count must stay on one line, got ${(count.bottom - count.top).value}dp",
        )

        tag("stickerManageDone").performClick()
        waitForIdle()
        tag("stickerSelectedCount").assertDoesNotExist()
        tag("stickerManageToggle").assertIsDisplayed()
    }

    // -----------------------------------------------------------------------------------------
    // C4 — semantics
    // -----------------------------------------------------------------------------------------

    /**
     * `Import` and `All` are plain buttons: a `selected` property on a non-tab node is reported as
     * checkable on Android, which announced both as "unchecked". Only the choice tiles keep it.
     */
    @Test
    fun importAndAllCarryNoSelectedSemantics() = runComposeUiTest {
        val userOne = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val harness = StickerHarness(stickers = listOf(userOne))
        lateinit var importLabel: String
        lateinit var allLabel: String
        setContent {
            AppTheme {
                importLabel = stringResource(Res.string.sticker_import)
                allLabel = stringResource(Res.string.sticker_all_open)
                Box(modifier = Modifier.width(360.dp)) {
                    StickerPanelHost(harness = harness, useLargeDialog = false)
                }
            }
        }
        assertFalse(
            tag("stickerAdd").hasSelectedProperty(),
            "Import must expose no `selected` property",
        )
        assertFalse(
            tag("stickerExpand").hasSelectedProperty(),
            "All must expose no `selected` property",
        )
        // The choice tiles keep reporting what is applied.
        assertTrue(tag("stickerClear").hasSelectedProperty(), "None is a choice tile")
        assertTrue(tag("stickerUser_user-1").hasSelectedProperty(), "sticker tiles are choice tiles")

        // Role and label are unchanged.
        assertEquals(Role.Button, tag("stickerAdd").role())
        assertEquals(Role.Button, tag("stickerExpand").role())
        describedAs("stickerAdd", importLabel).assertExists()
        describedAs("stickerExpand", allLabel).assertExists()
    }

    // -----------------------------------------------------------------------------------------
    // C1 — overlay height and preview refit
    // -----------------------------------------------------------------------------------------

    @Test
    fun overlayTakesTheContractHeightAndRefitsThePreviewBackAndForth() = runComposeUiTest {
        val photo = ImageInfoUi(MediaRef("/tmp/photo.jpg"), width = 400, height = 300)
        val harness = StickerHarness(icon = MediaRef("/tmp/icon-builtin-verified.png"))
        setContent {
            AppTheme {
                EditorScreenUnderTest(
                    harness = harness,
                    photo = photo,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        waitForIdle()
        val previewBefore = tag("sharedComposeWatermarkPreview").getUnclippedBoundsInRoot()

        tag("stickerExpand").performClick()
        awaitPanel()
        waitForIdle()

        val root = tag("sharedComposeEditorScreen").getUnclippedBoundsInRoot()
        val filmstrip = tag("editorMainFilmstrip").getUnclippedBoundsInRoot()
        val panel = tag("stickerPanelSheet").getUnclippedBoundsInRoot()
        val chrome = root.bottom - filmstrip.top
        (panel.bottom - panel.top).assertIsEqualTo(
            expectedOverlayHeight(rootHeight = root.bottom - root.top, bottomChromeHeight = chrome),
            "docked overlay height",
            tolerance = 2.dp,
        )

        val previewOpen = tag("sharedComposeWatermarkPreview").getUnclippedBoundsInRoot()
        assertTrue(
            previewOpen.bottom <= panel.top,
            "preview must refit above the overlay, got ${previewOpen.bottom.value}dp vs ${panel.top.value}dp",
        )
        assertTrue(
            previewOpen.bottom < previewBefore.bottom,
            "preview must shrink while the overlay is up",
        )

        tag("stickerSheetClose").performClick()
        awaitNoPanel()
        waitForIdle()
        val previewAfter = tag("sharedComposeWatermarkPreview").getUnclippedBoundsInRoot()
        previewAfter.bottom.assertIsEqualTo(
            previewBefore.bottom,
            "preview returns when the overlay goes away",
            tolerance = 1.dp,
        )
    }

    @Test
    fun shortWindowOverlayFallsBackToChromeHeightAndKeepsTheFooterPinned() = runComposeUiTest {
        val stickers = (1..12).map { index ->
            Sticker(id = "user-$index", icon = MediaRef("/tmp/icon-user-$index.png"))
        }
        val photo = ImageInfoUi(MediaRef("/tmp/photo.jpg"), width = 400, height = 300)
        val harness = StickerHarness(icon = stickers.first().icon, stickers = stickers)
        var canvasClicks = 0
        var backEnabled = false
        var triggerBack: (() -> Unit)? = null
        setContent {
            AppTheme {
                EditorScreenUnderTest(
                    harness = harness,
                    photo = photo,
                    modifier = Modifier
                        .width(360.dp)
                        .height(360.dp),
                    onCanvasClick = { canvasClicks += 1 },
                    overlayBackHandler = { enabled, onBack ->
                        backEnabled = enabled
                        triggerBack = onBack
                    },
                )
            }
        }
        waitForIdle()
        tag("stickerExpand").performClick()
        awaitPanel()
        waitForIdle()

        // 0.48 × 360dp is below the bottom chrome, so the chrome height wins (C1 `max`).
        val root = tag("sharedComposeEditorScreen").getUnclippedBoundsInRoot()
        val filmstrip = tag("editorMainFilmstrip").getUnclippedBoundsInRoot()
        val panel = tag("stickerPanelSheet").getUnclippedBoundsInRoot()
        val chrome = root.bottom - filmstrip.top
        (panel.bottom - panel.top).assertIsEqualTo(
            expectedOverlayHeight(rootHeight = root.bottom - root.top, bottomChromeHeight = chrome),
            "short-window overlay height",
            tolerance = 2.dp,
        )
        assertTrue(
            tag("sharedComposeWatermarkPreview").getUnclippedBoundsInRoot().bottom <= panel.top,
            "preview must refit above the overlay in a short window too",
        )

        // 12 user stickers in a short window: the footer is there before and after the grid scrolls.
        inPanel("stickerManageToggle").performClick()
        waitForIdle()
        inPanel("stickerManageBatchBar").assertIsDisplayed()
        inPanel("stickerManageSelectAll").assertIsDisplayed()
        inPanel("stickerManageDeleteSelected").assertIsDisplayed()

        // No 360.dp grid cap: one scrolling body, and it scrolls under the pinned footer.
        val body = onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag("stickerPanelSheet")),
            useUnmergedTree = true,
        )
        val scrolledBefore = body.verticalScrollOffset()
        body.performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(
            body.verticalScrollOffset() > scrolledBefore,
            "the panel body must scroll instead of capping the grid",
        )
        inPanel("stickerManageBatchBar").assertIsDisplayed()
        inPanel("stickerManageDeleteSelected").assertIsDisplayed()

        // The canvas above the docked panel still takes real touch.
        tag("testEditorCanvas").performTouchInput { click(Offset(centerX, 8.dp.toPx())) }
        runOnIdle {
            assertEquals(1, canvasClicks)
            assertTrue(backEnabled)
        }
        tag("stickerPanelSheet").assertIsDisplayed()

        runOnIdle { triggerBack?.invoke() }
        awaitNoPanel()
        runOnIdle { assertFalse(backEnabled) }
    }
}
