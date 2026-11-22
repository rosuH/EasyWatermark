package me.rosuh.easywatermark.uitest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_batch_confirm
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_selected
import me.rosuh.easywatermark.shared.generated.resources.sticker_deselect_all
import me.rosuh.easywatermark.shared.generated.resources.sticker_select_all
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.ui.EditorBottomOverlayHostState
import me.rosuh.easywatermark.ui.EditorLayoutClass
import me.rosuh.easywatermark.ui.EditorOptionItem
import me.rosuh.easywatermark.ui.EditorScreen
import me.rosuh.easywatermark.ui.EditorUiIcons
import me.rosuh.easywatermark.ui.LocalEditorBottomOverlayHost
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.compose.BuiltInSticker
import me.rosuh.easywatermark.ui.compose.StickerPanelOnIcon
import me.rosuh.easywatermark.ui.iconPainter
import me.rosuh.easywatermark.ui.label
import me.rosuh.easywatermark.ui.theme.AppTheme
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalTestApi::class)
class StickerPanelSheetUiTest {

    @Test
    fun inlineRailShowsAddAndBuiltInsDirectlyAndSwitchesLive() = runComposeUiTest {
        var appliedBuiltIn: BuiltInSticker? = null
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = MediaRef("/tmp/icon-builtin-copyright.png"),
            ),
        )
        setContent {
            AppTheme {
                StickerPanelOnIcon(
                    waterMark = mark,
                    userStickers = emptyList(),
                    useLargeDialog = false,
                    onAdd = {},
                    onApplyBuiltIn = { builtIn, _ ->
                        appliedBuiltIn = builtIn
                        mark = mark.copy(iconUri = MediaRef("/tmp/icon-builtin-${builtIn.key}.png"))
                    },
                    onApplyUser = {},
                    onDeleteUser = {},
                    onClear = {},
                )
            }
        }
        onNodeWithTag("stickerInlineRail", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerExpand", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerAdd", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerBuiltIn_copyright", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerBuiltIn_seal", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerCurrentCard", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerChange", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertDoesNotExist()

        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_copyright")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_seal")),
            useUnmergedTree = true,
        ).assertDoesNotExist()

        onNodeWithTag("stickerBuiltIn_seal", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(BuiltInSticker.Seal, appliedBuiltIn) }

        onNodeWithTag("stickerInlineRail", useUnmergedTree = true).assertIsDisplayed()
        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_seal")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_copyright")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun inlineRailClearButtonClearsActiveSticker() = runComposeUiTest {
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                tileMode = WatermarkTileMode.REPEAT,
                iconUri = MediaRef("/tmp/icon-builtin-shield.png"),
            ),
        )
        var clears = 0
        setContent {
            AppTheme {
                StickerPanelOnIcon(
                    waterMark = mark,
                    userStickers = emptyList(),
                    useLargeDialog = false,
                    onAdd = {},
                    onApplyBuiltIn = { _, _ -> },
                    onApplyUser = {},
                    onDeleteUser = {},
                    onClear = {
                        clears += 1
                        mark = mark.copy(iconUri = MediaRef.Empty)
                    },
                )
            }
        }
        onNodeWithTag("stickerClear", useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, clears) }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerClear", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag("stickerInlineRail", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun inlineRailUserStickerAppearsFirstAndLongPressDeletes() = runComposeUiTest {
        val userSticker = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = MediaRef.Empty,
            ),
        )
        var appliedUser: Sticker? = null
        var deleted: Sticker? = null
        lateinit var confirmLabel: String
        setContent {
            AppTheme {
                confirmLabel = stringResource(Res.string.tips_confirm_dialog)
                StickerPanelOnIcon(
                    waterMark = mark,
                    userStickers = listOf(userSticker),
                    useLargeDialog = false,
                    onAdd = {},
                    onApplyBuiltIn = { _, _ -> },
                    onApplyUser = { sticker ->
                        appliedUser = sticker
                        mark = mark.copy(iconUri = sticker.icon)
                    },
                    onDeleteUser = { deleted = it },
                    onClear = {},
                )
            }
        }
        onNodeWithTag("stickerInlineRail", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerUser_user-1", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        runOnIdle { assertEquals(userSticker, appliedUser) }
        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerUser_user-1")),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        onNodeWithTag("stickerUser_user-1", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText(confirmLabel, useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(userSticker, deleted) }
    }

    @Test
    fun inlineInspectorShowsGridDirectlyAndUpdatesSelectionLive() = runComposeUiTest {
        var appliedBuiltIn: BuiltInSticker? = null
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = MediaRef("/tmp/icon-builtin-copyright.png"),
            ),
        )
        setContent {
            AppTheme {
                StickerPanelOnIcon(
                    waterMark = mark,
                    userStickers = emptyList(),
                    useLargeDialog = true,
                    onAdd = {},
                    onApplyBuiltIn = { builtIn, _ ->
                        appliedBuiltIn = builtIn
                        mark = mark.copy(iconUri = MediaRef("/tmp/icon-builtin-${builtIn.key}.png"))
                    },
                    onApplyUser = {},
                    onDeleteUser = {},
                    onClear = {},
                )
            }
        }
        onNodeWithTag("stickerInlinePanel", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerAdd", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerBuiltIn_copyright", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerBuiltIn_seal", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerCurrentCard", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertDoesNotExist()

        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_copyright")),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        onNodeWithTag("stickerBuiltIn_seal", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(BuiltInSticker.Seal, appliedBuiltIn) }

        // Inline grid stays open for 1-click switching and badge moves to Seal.
        onNodeWithTag("stickerInlinePanel", useUnmergedTree = true).assertIsDisplayed()
        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_seal")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(
            hasTestTag("stickerSelectedBadge") and hasAnyAncestor(hasTestTag("stickerBuiltIn_copyright")),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun inlineInspectorClearAndUserLongPressDelete() = runComposeUiTest {
        val userSticker = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = userSticker.icon,
            ),
        )
        var clears = 0
        var deleted: Sticker? = null
        lateinit var confirmLabel: String
        setContent {
            AppTheme {
                confirmLabel = stringResource(Res.string.tips_confirm_dialog)
                StickerPanelOnIcon(
                    waterMark = mark,
                    userStickers = listOf(userSticker),
                    useLargeDialog = true,
                    onAdd = {},
                    onApplyBuiltIn = { _, _ -> },
                    onApplyUser = {},
                    onDeleteUser = { deleted = it },
                    onClear = {
                        clears += 1
                        mark = mark.copy(iconUri = MediaRef.Empty)
                    },
                )
            }
        }
        onNodeWithTag("stickerInlinePanel", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerClear", useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(1, clears) }
        onNodeWithTag("stickerClear", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerInlinePanel", useUnmergedTree = true).assertIsDisplayed()

        onNodeWithTag("stickerUser_user-1", useUnmergedTree = true).performTouchInput { longClick() }
        onNodeWithText(confirmLabel, useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(userSticker, deleted) }
    }

    @Test
    fun inlineRailExpandOpensGroupedPanelAndManagesUserStickers() = runComposeUiTest {
        val userSticker1 = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val userSticker2 = Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png"))
        var stickers by mutableStateOf(listOf(userSticker1, userSticker2))
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = userSticker1.icon,
            ),
        )
        var appliedBuiltIn: BuiltInSticker? = null
        var deletedBatch by mutableStateOf<List<Sticker>>(emptyList())
        var clears = 0
        lateinit var confirmLabel: String
        lateinit var selectAllLabel: String
        lateinit var deselectAllLabel: String
        lateinit var deleteZeroLabel: String
        lateinit var deleteOneLabel: String
        lateinit var deleteTwoLabel: String
        lateinit var batchConfirmTwoLabel: String
        setContent {
            AppTheme {
                confirmLabel = stringResource(Res.string.tips_confirm_dialog)
                selectAllLabel = stringResource(Res.string.sticker_select_all)
                deselectAllLabel = stringResource(Res.string.sticker_deselect_all)
                deleteZeroLabel = stringResource(Res.string.sticker_delete_selected, 0)
                deleteOneLabel = stringResource(Res.string.sticker_delete_selected, 1)
                deleteTwoLabel = stringResource(Res.string.sticker_delete_selected, 2)
                batchConfirmTwoLabel = stringResource(Res.string.sticker_delete_batch_confirm, 2)
                StickerPanelOnIcon(
                    waterMark = mark,
                    userStickers = stickers,
                    useLargeDialog = false,
                    onAdd = {},
                    onApplyBuiltIn = { builtIn, _ ->
                        appliedBuiltIn = builtIn
                        mark = mark.copy(iconUri = MediaRef("/tmp/icon-builtin-${builtIn.key}.png"))
                    },
                    onApplyUser = { mark = mark.copy(iconUri = it.icon) },
                    onDeleteUser = { target ->
                        stickers = stickers - target
                    },
                    onDeleteUsers = { targets ->
                        deletedBatch = targets
                        val targetIds = targets.map { it.id }.toSet()
                        stickers = stickers.filterNot { it.id in targetIds }
                    },
                    onClear = {
                        clears += 1
                        mark = mark.copy(iconUri = MediaRef.Empty)
                    },
                )
            }
        }

        onNodeWithTag("stickerExpand", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("stickerSectionMine", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerSectionBuiltIn", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageToggle", useUnmergedTree = true).assertIsDisplayed()

        // Remove chip in sheet header clears active icon and hides until reselected.
        onNodeWithTag("stickerSheetClear", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        runOnIdle { assertEquals(1, clears) }
        onNodeWithTag("stickerSheetClear", useUnmergedTree = true).assertDoesNotExist()

        // Selecting a built-in inside the sheet applies live without dismissing for fast comparison.
        onNode(
            hasTestTag("stickerBuiltIn_seal") and hasAnyAncestor(hasTestTag("stickerPanelSheet")),
            useUnmergedTree = true,
        ).performClick()
        runOnIdle { assertEquals(BuiltInSticker.Seal, appliedBuiltIn) }
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerSheetClear", useUnmergedTree = true).assertIsDisplayed()

        // Manage toggle hides header Remove and exposes multi-select checkboxes + batch bar.
        onNodeWithTag("stickerManageToggle", useUnmergedTree = true).performClick()
        onNodeWithTag("stickerSheetClear", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerManageBatchBar", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true).assertIsNotEnabled()
        onNode(
            hasText(deleteZeroLabel) and hasAnyAncestor(hasTestTag("stickerManageDeleteSelected")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(
            hasText(selectAllLabel) and hasAnyAncestor(hasTestTag("stickerManageSelectAll")),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        // Select one user sticker via its TopStart badge -> Delete (1) enables.
        onNodeWithTag("stickerManageSelect_user-1", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true).assertIsEnabled()
        onNode(
            hasText(deleteOneLabel) and hasAnyAncestor(hasTestTag("stickerManageDeleteSelected")),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        // Select all -> selects remaining sticker too and flips left pill to Clear.
        onNodeWithTag("stickerManageSelectAll", useUnmergedTree = true).performClick()
        onNode(
            hasText(deselectAllLabel) and hasAnyAncestor(hasTestTag("stickerManageSelectAll")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNode(
            hasText(deleteTwoLabel) and hasAnyAncestor(hasTestTag("stickerManageDeleteSelected")),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        // Clear selection -> back to disabled Delete (0), then Select all again and batch delete.
        onNodeWithTag("stickerManageSelectAll", useUnmergedTree = true).performClick()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag("stickerManageSelectAll", useUnmergedTree = true).performClick()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()

        onNodeWithText(batchConfirmTwoLabel, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmLabel, useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf(userSticker1, userSticker2), deletedBatch) }

        // All custom stickers removed -> Mine/Built-in headings and batch bar collapse cleanly.
        onNodeWithTag("stickerSectionMine", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerManageBatchBar", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerSheetClose", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun inlineInspectorGroupsSectionsAndSupportsManageDelete() = runComposeUiTest {
        val userSticker1 = Sticker(id = "user-1", icon = MediaRef("/tmp/icon-user-1.png"))
        val userSticker2 = Sticker(id = "user-2", icon = MediaRef("/tmp/icon-user-2.png"))
        val userSticker3 = Sticker(id = "user-3", icon = MediaRef("/tmp/icon-user-3.png"))
        var stickers by mutableStateOf(listOf(userSticker1, userSticker2, userSticker3))
        var deletedBatches by mutableStateOf<List<List<Sticker>>>(emptyList())
        lateinit var confirmLabel: String
        lateinit var deleteTwoLabel: String
        lateinit var deleteOneLabel: String
        lateinit var batchConfirmTwoLabel: String
        lateinit var batchConfirmOneLabel: String
        setContent {
            AppTheme {
                confirmLabel = stringResource(Res.string.tips_confirm_dialog)
                deleteTwoLabel = stringResource(Res.string.sticker_delete_selected, 2)
                deleteOneLabel = stringResource(Res.string.sticker_delete_selected, 1)
                batchConfirmTwoLabel = stringResource(Res.string.sticker_delete_batch_confirm, 2)
                batchConfirmOneLabel = stringResource(Res.string.sticker_delete_batch_confirm, 1)
                StickerPanelOnIcon(
                    waterMark = WaterMark.default.copy(
                        markMode = WatermarkMode.Image,
                        iconUri = userSticker1.icon,
                    ),
                    userStickers = stickers,
                    useLargeDialog = true,
                    onAdd = {},
                    onApplyBuiltIn = { _, _ -> },
                    onApplyUser = {},
                    onDeleteUser = { target -> stickers = stickers - target },
                    onDeleteUsers = { targets ->
                        deletedBatches = deletedBatches + listOf(targets)
                        val targetIds = targets.map { it.id }.toSet()
                        stickers = stickers.filterNot { it.id in targetIds }
                    },
                    onClear = {},
                )
            }
        }
        onNodeWithTag("stickerSectionMine", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerSectionBuiltIn", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageToggle", useUnmergedTree = true).performClick()
        onNodeWithTag("stickerManageBatchBar", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true).assertIsNotEnabled()

        // Multi-select two stickers (one via badge, one via tile tap) and batch delete them.
        onNodeWithTag("stickerManageSelect_user-1", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        onNodeWithTag("stickerUser_user-2", useUnmergedTree = true).performClick()
        onNode(
            hasText(deleteTwoLabel) and hasAnyAncestor(hasTestTag("stickerManageDeleteSelected")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()
        onNodeWithText(batchConfirmTwoLabel, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmLabel, useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(listOf(listOf(userSticker1, userSticker2)), deletedBatches)
            assertEquals(listOf(userSticker3), stickers)
        }

        // Remaining sticker stays in Manage mode with empty selection until deleted too.
        onNodeWithTag("stickerSectionMine", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag("stickerManageSelectAll", useUnmergedTree = true).performClick()
        onNode(
            hasText(deleteOneLabel) and hasAnyAncestor(hasTestTag("stickerManageDeleteSelected")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()
        onNodeWithText(batchConfirmOneLabel, useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText(confirmLabel, useUnmergedTree = true).assertIsDisplayed().performClick()
        runOnIdle { assertEquals(emptyList(), stickers) }
        onNodeWithTag("stickerSectionMine", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("stickerManageBatchBar", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun dockedStickerSheetAllowsLiveCanvasInteractionAbove() = runComposeUiTest {
        val host = EditorBottomOverlayHostState()
        var canvasClicks = 0
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = MediaRef("/tmp/icon-builtin-copyright.png"),
            ),
        )
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalEditorBottomOverlayHost provides host) {
                    Box(
                        modifier = Modifier
                            .width(390.dp)
                            .fillMaxHeight(),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clickable { canvasClicks += 1 }
                                    .testTag("testLiveCanvas"),
                            )
                            StickerPanelOnIcon(
                                waterMark = mark,
                                userStickers = emptyList(),
                                useLargeDialog = false,
                                onAdd = {},
                                onApplyBuiltIn = { builtIn, _ ->
                                    mark = mark.copy(iconUri = MediaRef("/tmp/icon-builtin-${builtIn.key}.png"))
                                },
                                onApplyUser = {},
                                onDeleteUser = {},
                                onClear = {},
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                        ) {
                            host.content?.invoke()
                        }
                    }
                }
            }
        }

        onNodeWithTag("stickerExpand", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertIsDisplayed()

        // Canvas above the docked panel receives real pointer touch while the sheet stays open.
        onNodeWithTag("testLiveCanvas", useUnmergedTree = true).performTouchInput {
            click(center)
        }
        runOnIdle { assertEquals(1, canvasClicks) }
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertIsDisplayed()

        // Short vertical drag (< 48.dp) resets on release without dismissing.
        onNodeWithTag("stickerSheetDragHandle", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, 16.dp.toPx()))
            moveBy(Offset(0f, 16.dp.toPx()))
            up()
        }
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertIsDisplayed()

        // Downward drag past 48.dp dismisses the docked sheet cleanly.
        onNodeWithTag("stickerSheetDragHandle", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, 24.dp.toPx()))
            moveBy(Offset(0f, 64.dp.toPx()))
            up()
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun dockedStickerSheetInShortWindowCapsHeightAndKeepsManageBarAndCanvasInteractive() = runComposeUiTest {
        val manyStickers = (1..12).map { index ->
            Sticker(id = "user-$index", icon = MediaRef("/tmp/icon-user-$index.png"))
        }
        val selectedPhoto = ImageInfoUi(MediaRef("/tmp/photo.jpg"), width = 400, height = 300)
        var mark by mutableStateOf(
            WaterMark.default.copy(
                markMode = WatermarkMode.Image,
                iconUri = manyStickers.first().icon,
            ),
        )
        var canvasClicks = 0
        var backEnabled = false
        var triggerBack: (() -> Unit)? = null

        setContent {
            AppTheme {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .height(360.dp),
                ) {
                    EditorScreen(
                        imageList = emptyList(),
                        waterMark = mark,
                        selectedImage = selectedPhoto,
                        templates = emptyList(),
                        icons = EditorUiIcons(
                            back = SharedProductDrawables.backPainter(),
                            addMoreImages = SharedProductDrawables.pickerImagePainter(),
                            save = SharedProductDrawables.savePainter(),
                            about = SharedProductDrawables.aboutPainter(),
                            templateList = SharedProductDrawables.templateListPainter(),
                        ),
                        preview = { previewModifier ->
                            Box(
                                modifier = previewModifier
                                    .clickable { canvasClicks += 1 }
                                    .testTag("testShortWindowCanvas"),
                            )
                        },
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
                        iconOption = { optionModifier, currentMark, _ ->
                            StickerPanelOnIcon(
                                waterMark = currentMark,
                                userStickers = manyStickers,
                                modifier = optionModifier,
                                useLargeDialog = false,
                                onAdd = {},
                                onApplyBuiltIn = { _, _ -> },
                                onApplyUser = {},
                                onDeleteUser = {},
                                onClear = {},
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
                        layoutClass = EditorLayoutClass.Compact,
                        forcedBottomTab = 0,
                        forcedOptionIndex = 1,
                        overlayBackHandler = { enabled, onBack ->
                            backEnabled = enabled
                            triggerBack = onBack
                        },
                    )
                }
            }
        }

        onNodeWithTag("stickerExpand", useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("stickerManageToggle", useUnmergedTree = true).performClick()

        // Even with 12+ stickers overflowing the 360dp-tall window cap, batch bar stays pinned and visible.
        onNodeWithTag("stickerManageBatchBar", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageSelectAll", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("stickerManageDeleteSelected", useUnmergedTree = true).assertIsDisplayed()

        // Canvas above the capped docked sheet remains tappable via real touch.
        onNodeWithTag("testShortWindowCanvas", useUnmergedTree = true).performTouchInput {
            click(Offset(centerX, 16.dp.toPx()))
        }
        runOnIdle {
            assertEquals(1, canvasClicks)
            assertTrue(backEnabled)
        }
        onNodeWithTag("stickerPanelSheet", useUnmergedTree = true).assertIsDisplayed()

        // Back handler dismisses the docked overlay cleanly.
        runOnIdle { triggerBack?.invoke() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("stickerPanelSheet", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        runOnIdle { assertFalse(backEnabled) }
    }

    @Test
    fun builtInStickerFromIconMatchesUnixAndWindowsPaths() {
        assertEquals(
            BuiltInSticker.Copyright,
            BuiltInSticker.fromIcon(MediaRef("/tmp/stickers/icon-builtin-copyright.png")),
        )
        assertEquals(
            BuiltInSticker.Seal,
            BuiltInSticker.fromIcon(MediaRef("C:\\Users\\rosu\\AppData\\Local\\icon-builtin-seal.png")),
        )
        assertEquals(
            BuiltInSticker.Lock,
            BuiltInSticker.fromIcon(MediaRef("C:\\mixed/dir\\icon_builtin_lock.png")),
        )
        assertEquals(
            null,
            BuiltInSticker.fromIcon(MediaRef("C:\\Users\\rosu\\icon-user-1.png")),
        )
    }
}
