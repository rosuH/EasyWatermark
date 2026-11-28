package me.rosuh.easywatermark.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.render.WatermarkIconCache
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cd_sticker_close
import me.rosuh.easywatermark.shared.generated.resources.ic_close_24dp
import me.rosuh.easywatermark.shared.generated.resources.ic_save_done
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_anti_theft
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_do_not_copy
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_no_ai
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_no_reprint
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_no_repost
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_original
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_private
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_rights_reserved
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_sample
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_seal_original
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_smiley
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_verified
import me.rosuh.easywatermark.shared.generated.resources.preview_selection_done
import me.rosuh.easywatermark.shared.generated.resources.sticker_all_open
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_batch_confirm
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_selected
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_title
import me.rosuh.easywatermark.shared.generated.resources.sticker_deselect_all
import me.rosuh.easywatermark.shared.generated.resources.sticker_import
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_anti_theft
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_do_not_copy
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_no_ai
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_no_reprint
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_no_repost
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_original
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_private
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_rights_reserved
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_sample
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_seal_original
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_smiley
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_verified
import me.rosuh.easywatermark.shared.generated.resources.sticker_manage
import me.rosuh.easywatermark.shared.generated.resources.sticker_none
import me.rosuh.easywatermark.shared.generated.resources.sticker_section_builtin
import me.rosuh.easywatermark.shared.generated.resources.sticker_section_mine
import me.rosuh.easywatermark.shared.generated.resources.sticker_select_all
import me.rosuh.easywatermark.shared.generated.resources.sticker_selected_count
import me.rosuh.easywatermark.shared.generated.resources.template_delete
import me.rosuh.easywatermark.shared.generated.resources.tips_cancel_dialog
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_image
import me.rosuh.easywatermark.ui.LocalEditorBottomOverlayHost
import me.rosuh.easywatermark.ui.ewmTestTagsAsResourceId
import me.rosuh.easywatermark.ui.image.ProductAsyncImage
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.theme.EwmMotionTokens
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.editorAccentColor
import me.rosuh.easywatermark.ui.theme.editorChromeColor
import me.rosuh.easywatermark.ui.theme.editorSelectedContainerColor
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The 12 built-in marks (ADR-0041). Raster art, drawn untinted — the art carries its own colour,
 * so the enum order here is the display order in every surface.
 *
 * `rights_reserved` deliberately keeps the old `copyright` art slot renamed: iOS reuses an existing
 * `icon_builtin_<key>.png` and devices in the field still hold the old white mark.
 */
enum class BuiltInSticker(
    val key: String,
    val drawable: DrawableResource,
    val label: StringResource,
) {
    Verified("verified", Res.drawable.ic_sticker_verified, Res.string.sticker_label_verified),
    Original("original", Res.drawable.ic_sticker_original, Res.string.sticker_label_original),
    NoAi("no_ai", Res.drawable.ic_sticker_no_ai, Res.string.sticker_label_no_ai),
    NoRepost("no_repost", Res.drawable.ic_sticker_no_repost, Res.string.sticker_label_no_repost),
    DoNotCopy(
        "do_not_copy",
        Res.drawable.ic_sticker_do_not_copy,
        Res.string.sticker_label_do_not_copy,
    ),
    RightsReserved(
        "rights_reserved",
        Res.drawable.ic_sticker_rights_reserved,
        Res.string.sticker_label_rights_reserved,
    ),
    Sample("sample", Res.drawable.ic_sticker_sample, Res.string.sticker_label_sample),
    Private("private", Res.drawable.ic_sticker_private, Res.string.sticker_label_private),
    Smiley("smiley", Res.drawable.ic_sticker_smiley, Res.string.sticker_label_smiley),
    SealOriginal(
        "seal_original",
        Res.drawable.ic_sticker_seal_original,
        Res.string.sticker_label_seal_original,
    ),
    NoReprint("no_reprint", Res.drawable.ic_sticker_no_reprint, Res.string.sticker_label_no_reprint),
    AntiTheft("anti_theft", Res.drawable.ic_sticker_anti_theft, Res.string.sticker_label_anti_theft),
    ;

    fun matches(ref: MediaRef): Boolean {
        val fileName = ref.value.substringAfterLast('/').substringAfterLast('\\')
        return fileName == "icon-builtin-$key.png" || fileName == "icon_builtin_$key.png"
    }

    companion object {
        fun fromIcon(ref: MediaRef): BuiltInSticker? = entries.firstOrNull { it.matches(ref) }
    }
}

/**
 * Rasterise a sticker painter for the render pipeline: aspect ratio preserved, long edge
 * [maxEdgePx] (the pipeline's icon decode bound), no tint — the art owns its colour.
 */
fun Painter.toStickerBitmap(
    maxEdgePx: Int = WatermarkIconCache.ICON_MAX_EDGE_PX,
): ImageBitmap {
    val edge = maxEdgePx.coerceAtLeast(1)
    val intrinsic = intrinsicSize
    val ratio = if (
        intrinsic.width > 0f && intrinsic.height > 0f &&
        intrinsic.width.isFinite() && intrinsic.height.isFinite()
    ) {
        intrinsic.width / intrinsic.height
    } else {
        1f
    }
    val widthPx: Int
    val heightPx: Int
    if (ratio >= 1f) {
        widthPx = edge
        heightPx = (edge / ratio).roundToInt().coerceIn(1, edge)
    } else {
        heightPx = edge
        widthPx = (edge * ratio).roundToInt().coerceIn(1, edge)
    }
    val bitmap = ImageBitmap(widthPx, heightPx)
    val target = Size(widthPx.toFloat(), heightPx.toFloat())
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = target,
    ) {
        draw(size = target)
    }
    return bitmap
}

/** Bumped every time the Icon control is tapped, including a tap on the already selected chip. */
val LocalStickerSheetRequest = staticCompositionLocalOf { 0 }

private val StickerTileShape = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp)
private val CheckboxShape = RoundedCornerShape(2.dp)
private val CornerDeleteShape = RoundedCornerShape(bottomEnd = 2.dp)

private val RailTileSize = 56.dp
private val RailSpacing = 6.dp
private val RailStartPadding = 12.dp

/** Width the pinned `All` tile reserves at the end of the rail (56 tile + 12 end padding). */
private val RailAllReserve = 68.dp
private val RailAllEndPadding = 12.dp

/** Fade over the end of the list viewport, so the last visible tile has no hard cut. */
private val RailScrimWidth = 26.dp

/** Wide Import grows with its label (longer locales) instead of clipping it at a fixed 136 dp. */
private val WideImportMinWidth = 136.dp
private val WideImportMaxWidth = 220.dp

private val GridGap = 4.dp
private val GridSidePadding = 16.dp

/** Empty library: the labelled Import tile takes the rest of its row (columns 2…4), no hole. */
private const val WIDE_IMPORT_SPAN = 3

private val UtilityIconSize = 22.dp
private val CheckboxSize = 18.dp
private val PanelHeaderHeight = 44.dp
private val SectionRowHeight = 32.dp
private val SectionRowActionHeight = 40.dp
private val DeleteFooterHeight = 48.dp
private val HoverDeleteSize = 16.dp

private const val BUILT_IN_ART_FRACTION = 0.84f
private const val USER_ART_FRACTION = 0.78f
private const val DIMMED_ALPHA = 0.3f

private sealed interface StickerCell {
    data object None : StickerCell
    data object Import : StickerCell
    data class BuiltIn(val sticker: BuiltInSticker) : StickerCell
    data class User(val sticker: Sticker) : StickerCell
}

/** The three vector glyphs the utility tiles draw themselves, so none of them borrows an icon. */
private enum class StickerGlyph { None, Import, All }

/**
 * Rail order: the applied built-in first (so the current mark is always reachable without
 * scrolling), then the host's user stickers (already most-recent-first), then the rest of the
 * built-ins in enum order. Snapshotted by the caller — a tap must never reorder tiles under
 * the finger.
 */
private fun stickerRailOrder(
    userStickers: List<Sticker>,
    currentIcon: MediaRef,
): List<StickerCell> {
    val applied = BuiltInSticker.fromIcon(currentIcon)
    return buildList {
        if (applied != null) add(StickerCell.BuiltIn(applied))
        userStickers.forEach { add(StickerCell.User(it)) }
        BuiltInSticker.entries.forEach { if (it != applied) add(StickerCell.BuiltIn(it)) }
    }
}

/**
 * Height for the wide Import tile: exactly one cell, not a fraction of its own width. The span also
 * swallows the gaps between its columns, so an `aspectRatio` would make it taller than every other
 * tile in its row.
 */
private fun Modifier.wideImportCellHeight(
    span: Int = WIDE_IMPORT_SPAN,
    gap: Dp = GridGap,
) = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = ((width - gap.roundToPx() * (span - 1)) / span).coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private fun StickerCell.key(): String = when (this) {
    StickerCell.None -> "none"
    StickerCell.Import -> "add"
    is StickerCell.BuiltIn -> "builtin_${sticker.key}"
    is StickerCell.User -> "user_${sticker.id}"
}

/**
 * Docked sticker panel (compact/medium) and dialog body (large surfaces): one scrolling body of
 * `None` + `Import` + user stickers and the built-in pack, with an in-place selection mode for
 * batch deletion. Applying a sticker swaps only the watermark image (ADR-0041) and keeps the
 * panel open.
 */
@Composable
fun StickerPanelSheet(
    userStickers: List<Sticker>,
    enabled: Boolean = true,
    useLargeDialog: Boolean = false,
    currentIcon: MediaRef = MediaRef.Empty,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onDeleteUser: (Sticker) -> Unit,
    onClear: (() -> Unit)? = null,
    dismissOnSelect: Boolean = false,
    onDeleteUsers: ((List<Sticker>) -> Unit)? = null,
) {
    StickerPanelSheetImpl(
        userStickers = userStickers,
        enabled = enabled,
        useLargeDialog = useLargeDialog,
        currentIcon = currentIcon,
        initialSelectedId = null,
        onDismiss = onDismiss,
        onAdd = onAdd,
        onApplyBuiltIn = onApplyBuiltIn,
        onApplyUser = onApplyUser,
        onDeleteUser = onDeleteUser,
        onClear = onClear,
        dismissOnSelect = dismissOnSelect,
        onDeleteUsers = onDeleteUsers,
    )
}

/**
 * [StickerPanelSheet] plus the one thing the frozen public signature cannot carry: the sticker a
 * rail long-press pre-selected, which opens the panel straight into selection mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerPanelSheetImpl(
    userStickers: List<Sticker>,
    enabled: Boolean,
    useLargeDialog: Boolean,
    currentIcon: MediaRef,
    initialSelectedId: String?,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onDeleteUser: (Sticker) -> Unit,
    onClear: (() -> Unit)?,
    dismissOnSelect: Boolean,
    onDeleteUsers: ((List<Sticker>) -> Unit)?,
) {
    val deleteUsers: (List<Sticker>) -> Unit = onDeleteUsers ?: { list -> list.forEach(onDeleteUser) }
    var pendingDelete by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var managing by remember { mutableStateOf(initialSelectedId != null) }
    var selectedIds by remember {
        mutableStateOf(initialSelectedId?.let { setOf(it) } ?: emptySet<String>())
    }
    LaunchedEffect(userStickers.isEmpty()) {
        if (userStickers.isEmpty()) managing = false
    }
    LaunchedEffect(managing, userStickers) {
        selectedIds = reconcileStickerSelection(managing, userStickers, selectedIds)
    }
    val body: @Composable (docked: Boolean) -> Unit = { docked ->
        StickerPanelBody(
            userStickers = userStickers,
            currentIcon = currentIcon,
            enabled = enabled,
            managing = managing,
            selectedIds = selectedIds,
            docked = docked,
            applyBottomInset = !useLargeDialog,
            onDismiss = onDismiss,
            onStartManaging = { managing = true },
            onStopManaging = { managing = false },
            onToggleSelectAll = {
                selectedIds = if (userStickers.all { it.id in selectedIds }) {
                    emptySet()
                } else {
                    userStickers.map { it.id }.toSet()
                }
            },
            onToggleSelect = { sticker ->
                selectedIds = toggleStickerSelection(selectedIds, sticker.id)
            },
            onRequestDeleteSelected = {
                pendingDelete = userStickers.filter { it.id in selectedIds }
            },
            onAdd = onAdd,
            onApplyBuiltIn = { builtIn, bitmap ->
                onApplyBuiltIn(builtIn, bitmap)
                if (dismissOnSelect) onDismiss()
            },
            onApplyUser = { sticker ->
                onApplyUser(sticker)
                if (dismissOnSelect) onDismiss()
            },
            onClear = onClear,
            onLongPressUser = { sticker ->
                managing = true
                selectedIds = setOf(sticker.id)
            },
        )
    }
    val overlayHost = LocalEditorBottomOverlayHost.current
    if (useLargeDialog) {
        EwmContentDialog(
            onDismissRequest = onDismiss,
            maxWidth = 380.dp,
            scrollContent = false,
        ) { body(false) }
    } else if (overlayHost != null) {
        val overlayToken = remember { Any() }
        val latestOnDismiss by rememberUpdatedState(onDismiss)
        SideEffect {
            overlayHost.show(
                token = overlayToken,
                onDismiss = { latestOnDismiss() },
            ) {
                DockedStickerOverlaySurface(onDismiss = { latestOnDismiss() }) { body(true) }
            }
        }
        DisposableEffect(overlayHost, overlayToken) {
            onDispose { overlayHost.clear(overlayToken) }
        }
    } else {
        EwmModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            scrimColor = Color.Transparent,
        ) { body(false) }
    }
    StickerDeleteConfirmDialog(
        targets = pendingDelete,
        onDismiss = { pendingDelete = emptyList() },
        onConfirmDelete = { targets ->
            pendingDelete = emptyList()
            selectedIds = emptySet()
            managing = false
            deleteUsers(targets)
        },
    )
}

@Composable
private fun StickerPanelBody(
    userStickers: List<Sticker>,
    currentIcon: MediaRef,
    enabled: Boolean,
    managing: Boolean,
    selectedIds: Set<String>,
    /**
     * Docked overlay: fill the fixed height C1 hands down, and let
     * [DockedStickerOverlaySurface] own the `stickerPanelSheet` tag so the tagged node is the
     * whole panel (handle row included). Dialog / modal sheet: wrap height and tag this body.
     */
    docked: Boolean,
    applyBottomInset: Boolean,
    onDismiss: () -> Unit,
    onStartManaging: () -> Unit,
    onStopManaging: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleSelect: (Sticker) -> Unit,
    onRequestDeleteSelected: () -> Unit,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onClear: (() -> Unit)?,
    onLongPressUser: (Sticker) -> Unit,
) {
    val closeLabel = stringResource(Res.string.cd_sticker_close)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (docked) Modifier.fillMaxHeight() else Modifier)
            .then(if (applyBottomInset) Modifier.navigationBarsPadding() else Modifier)
            .then(if (docked) Modifier else Modifier.testTag("stickerPanelSheet")),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PanelHeaderHeight)
                .padding(start = 20.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (managing) {
                StickerSelectionHeader(
                    count = stringResource(Res.string.sticker_selected_count, selectedIds.size),
                    countStyle = MaterialTheme.typography.titleMedium,
                    allSelected = userStickers.isNotEmpty() &&
                        userStickers.all { it.id in selectedIds },
                    enabled = enabled,
                    compact = false,
                    modifier = Modifier.fillMaxWidth(),
                    onToggleSelectAll = onToggleSelectAll,
                    onDone = onStopManaging,
                )
            } else {
                Text(
                    text = stringResource(Res.string.water_mark_mode_image),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .semantics { contentDescription = closeLabel }
                        .testTag("stickerSheetClose"),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_close_24dp),
                        contentDescription = closeLabel,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = docked),
            contentPadding = PaddingValues(
                start = GridSidePadding,
                end = GridSidePadding,
                bottom = if (managing) 12.dp else 44.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
            verticalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            if (userStickers.isNotEmpty()) {
                item(
                    key = "section_mine",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "section",
                ) {
                    // Selection mode owns the header row instead, so Manage steps aside.
                    val manageButton: (@Composable () -> Unit)? = if (managing) {
                        null
                    } else {
                        {
                            StickerTextButton(
                                text = stringResource(Res.string.sticker_manage),
                                tag = "stickerManageToggle",
                                enabled = enabled,
                                onClick = onStartManaging,
                            )
                        }
                    }
                    StickerSectionRow(
                        text = stringResource(Res.string.sticker_section_mine),
                        tag = "stickerSectionMine",
                        action = manageButton,
                        // Manage steps aside in selection mode; the row keeps its height so the
                        // grid does not jump.
                        height = SectionRowActionHeight,
                    )
                }
            }
            item(
                key = "none",
                contentType = "none",
            ) {
                StickerCellTile(
                    cell = StickerCell.None,
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    dimmed = managing,
                    noneTag = "stickerSheetClear",
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                )
            }
            item(
                key = "add",
                span = {
                    if (userStickers.isEmpty()) GridItemSpan(WIDE_IMPORT_SPAN) else GridItemSpan(1)
                },
                contentType = "add",
            ) {
                StickerCellTile(
                    cell = StickerCell.Import,
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = if (userStickers.isEmpty()) {
                        Modifier.fillMaxWidth().wideImportCellHeight()
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(1f)
                    },
                    dimmed = managing,
                    wideImport = userStickers.isEmpty(),
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                )
            }
            items(
                items = userStickers,
                key = { sticker -> "user_${sticker.id}" },
                contentType = { "user" },
            ) { sticker ->
                StickerCellTile(
                    cell = StickerCell.User(sticker),
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    selecting = managing,
                    picked = sticker.id in selectedIds,
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                    onLongPressUser = onLongPressUser,
                    onToggleUser = onToggleSelect,
                )
            }
            item(
                key = "section_builtin",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "section",
            ) {
                StickerSectionRow(
                    text = stringResource(Res.string.sticker_section_builtin),
                    tag = "stickerSectionBuiltIn",
                    dimmed = managing,
                )
            }
            items(
                items = BuiltInSticker.entries,
                key = { builtIn -> "builtin_${builtIn.key}" },
                contentType = { "builtin" },
            ) { builtIn ->
                StickerCellTile(
                    cell = StickerCell.BuiltIn(builtIn),
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    dimmed = managing,
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                )
            }
        }
        val fadeMs = motionDurationMs(currentMotionPolicy(), EwmMotionTokens.optionPanelFadeMs)
        AnimatedVisibility(
            visible = managing && userStickers.isNotEmpty(),
            enter = fadeIn(tween(fadeMs)),
            exit = fadeOut(tween(fadeMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            ) {
                // Tag the inner box: the footer inset belongs to the padded wrapper, so the
                // container and the full-width button measure the same width.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stickerManageBatchBar"),
                ) {
                    StickerDeleteSelectedButton(
                        selectedCount = selectedIds.size,
                        enabled = enabled,
                        onClick = onRequestDeleteSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun DockedStickerOverlaySurface(
    onDismiss: () -> Unit,
    body: @Composable () -> Unit,
) {
    val baseShape = EwmTheme.panel.shape
    val dockedShape = if (baseShape is CornerBasedShape) {
        baseShape.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
    } else {
        baseShape
    }
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .ewmTestTagsAsResourceId()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("stickerPanelSheet"),
        shape = dockedShape,
        color = EwmTheme.panel.containerColor,
        tonalElevation = EwmTheme.panel.tonalElevation,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.11f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(Unit) {
                        val thresholdPx = 48.dp.toPx()
                        var totalDragY = 0f
                        var dismissed = false
                        detectVerticalDragGestures(
                            onDragStart = {
                                totalDragY = 0f
                                dismissed = false
                            },
                            onDragEnd = {
                                totalDragY = 0f
                                dismissed = false
                            },
                            onDragCancel = {
                                totalDragY = 0f
                                dismissed = false
                            },
                            onVerticalDrag = { _, dragAmount ->
                                totalDragY = (totalDragY + dragAmount).coerceAtLeast(0f)
                                if (!dismissed && totalDragY >= thresholdPx) {
                                    dismissed = true
                                    latestOnDismiss()
                                }
                            },
                        )
                    }
                    .testTag("stickerSheetDragHandle"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.24f)),
                )
            }
            body()
        }
    }
}

/**
 * Compact/medium: a horizontal quick-pick rail with `None` and `Import` first and a pinned `All`
 * tile that opens [StickerPanelSheet]. Expanded/wide: the same grid body embedded in the
 * inspector ([useLargeDialog] = true) so the preview stays unobstructed.
 */
@Composable
fun StickerPanelOnIcon(
    waterMark: WaterMark,
    userStickers: List<Sticker>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    useLargeDialog: Boolean = false,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onDeleteUser: (Sticker) -> Unit,
    onClear: (() -> Unit)? = null,
    onDeleteUsers: ((List<Sticker>) -> Unit)? = null,
) {
    if (useLargeDialog) {
        InlineStickerInspectorPanel(
            currentIcon = waterMark.iconUri,
            userStickers = userStickers,
            modifier = modifier,
            enabled = enabled,
            onAdd = onAdd,
            onApplyBuiltIn = onApplyBuiltIn,
            onApplyUser = onApplyUser,
            onDeleteUser = onDeleteUser,
            onClear = onClear,
            onDeleteUsers = onDeleteUsers,
        )
    } else {
        InlineStickerRail(
            currentIcon = waterMark.iconUri,
            userStickers = userStickers,
            modifier = modifier,
            enabled = enabled,
            onAdd = onAdd,
            onApplyBuiltIn = onApplyBuiltIn,
            onApplyUser = onApplyUser,
            onDeleteUser = onDeleteUser,
            onClear = onClear,
            onDeleteUsers = onDeleteUsers,
        )
    }
}

@Composable
private fun InlineStickerRail(
    currentIcon: MediaRef,
    userStickers: List<Sticker>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onDeleteUser: (Sticker) -> Unit,
    onClear: (() -> Unit)? = null,
    onDeleteUsers: ((List<Sticker>) -> Unit)? = null,
) {
    var showSheet by remember { mutableStateOf(false) }
    var sheetSelectedId by remember { mutableStateOf<String?>(null) }
    // Re-snapshot the tile order on entry, when the panel closes, and when the list changes —
    // never on a tap, which would move tiles under the finger.
    var orderEpoch by remember { mutableIntStateOf(0) }
    val cells = remember(userStickers, orderEpoch) {
        stickerRailOrder(userStickers, currentIcon)
    }
    val allStickersLabel = stringResource(Res.string.sticker_all_open)
    val chrome = editorChromeColor()
    // `horizontalGradient` is physical while `CenterEnd` mirrors, so pick the stops by direction:
    // chrome always sits on the viewport's end edge and fades out towards the list.
    val layoutDirection = LocalLayoutDirection.current
    val railScrim = remember(chrome, layoutDirection) {
        Brush.horizontalGradient(
            if (layoutDirection == LayoutDirection.Rtl) {
                listOf(chrome, Color.Transparent)
            } else {
                listOf(Color.Transparent, chrome)
            },
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RailTileSize)
            .testTag("stickerInlineRail"),
    ) {
        LazyRow(
            // The All reserve is an inset on the viewport, not content padding: no tile is drawn
            // or hit-tested in the last 68 dp, so a tap there cannot apply an invisible sticker.
            modifier = Modifier.fillMaxSize().padding(end = RailAllReserve),
            contentPadding = PaddingValues(start = RailStartPadding, end = RailScrimWidth),
            horizontalArrangement = Arrangement.spacedBy(RailSpacing),
            verticalAlignment = Alignment.CenterVertically,
            overscrollEffect = rememberOverscrollEffect(),
        ) {
            item(key = "none", contentType = "none") {
                StickerCellTile(
                    cell = StickerCell.None,
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = Modifier.size(RailTileSize),
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                )
            }
            item(key = "add", contentType = "add") {
                StickerCellTile(
                    cell = StickerCell.Import,
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = if (userStickers.isEmpty()) {
                        Modifier
                            .widthIn(min = WideImportMinWidth, max = WideImportMaxWidth)
                            .height(RailTileSize)
                    } else {
                        Modifier.size(RailTileSize)
                    },
                    wideImport = userStickers.isEmpty(),
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                )
            }
            items(
                items = cells,
                key = { cell -> cell.key() },
                contentType = { cell -> if (cell is StickerCell.User) "user" else "builtin" },
            ) { cell ->
                StickerCellTile(
                    cell = cell,
                    currentIcon = currentIcon,
                    enabled = enabled,
                    modifier = Modifier.size(RailTileSize),
                    onClear = onClear,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                    onLongPressUser = { sticker ->
                        sheetSelectedId = sticker.id
                        showSheet = true
                    },
                )
            }
        }
        // Paint only (no pointer input): the half-visible tile under the fade stays tappable and
        // the list stays scrollable from here.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = RailAllReserve)
                .fillMaxHeight()
                .width(RailScrimWidth)
                .background(railScrim),
        )
        // The end inset lives on this wrapper so the tagged tile measures exactly 56 × 56.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = RailAllEndPadding),
        ) {
            StickerUtilityTile(
                isSelected = false,
                enabled = enabled,
                label = allStickersLabel,
                role = Role.Button,
                modifier = Modifier
                    .size(RailTileSize)
                    .testTag("stickerExpand"),
                onClick = {
                    sheetSelectedId = null
                    showSheet = true
                },
            ) {
                StickerUtilityGlyph(
                    glyph = StickerGlyph.All,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                    modifier = Modifier.size(UtilityIconSize),
                )
            }
        }
    }
    if (showSheet) {
        StickerPanelSheetImpl(
            userStickers = userStickers,
            enabled = enabled,
            useLargeDialog = false,
            currentIcon = currentIcon,
            initialSelectedId = sheetSelectedId,
            onDismiss = {
                showSheet = false
                sheetSelectedId = null
                orderEpoch++
            },
            onAdd = onAdd,
            onApplyBuiltIn = onApplyBuiltIn,
            onApplyUser = onApplyUser,
            onDeleteUser = onDeleteUser,
            onClear = onClear,
            dismissOnSelect = false,
            onDeleteUsers = onDeleteUsers,
        )
    }
}

@Composable
private fun InlineStickerInspectorPanel(
    currentIcon: MediaRef,
    userStickers: List<Sticker>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onDeleteUser: (Sticker) -> Unit,
    onClear: (() -> Unit)? = null,
    onDeleteUsers: ((List<Sticker>) -> Unit)? = null,
) {
    val deleteUsers: (List<Sticker>) -> Unit = onDeleteUsers ?: { list -> list.forEach(onDeleteUser) }
    var pendingDelete by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(userStickers.isEmpty()) {
        if (userStickers.isEmpty()) managing = false
    }
    LaunchedEffect(managing, userStickers) {
        selectedIds = reconcileStickerSelection(managing, userStickers, selectedIds)
    }
    // `stickerInlinePanel` gets a node of its own: on the inspector form path the modifier handed
    // down already carries `editorControl-Icon`, and two test tags on one layout node keep only the
    // outer one — which left this tag unreachable in the real expanded editor.
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("stickerInlinePanel"),
            verticalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            if (userStickers.isNotEmpty()) {
                if (managing) {
                    StickerSelectionHeader(
                        count = stringResource(Res.string.sticker_selected_count, selectedIds.size),
                        countStyle = MaterialTheme.typography.labelLarge,
                        allSelected = userStickers.all { it.id in selectedIds },
                        enabled = enabled,
                        compact = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SectionRowActionHeight),
                        onToggleSelectAll = {
                            selectedIds = if (userStickers.all { it.id in selectedIds }) {
                                emptySet()
                            } else {
                                userStickers.map { it.id }.toSet()
                            }
                        },
                        onDone = { managing = false },
                    )
                } else {
                    StickerSectionRow(
                        text = stringResource(Res.string.sticker_section_mine),
                        tag = "stickerSectionMine",
                        action = {
                            StickerTextButton(
                                text = stringResource(Res.string.sticker_manage),
                                tag = "stickerManageToggle",
                                enabled = enabled,
                                onClick = { managing = true },
                            )
                        },
                    )
                }
            }
            InspectorCellGridRows(
                cells = buildList {
                    add(StickerCell.None)
                    add(StickerCell.Import)
                    userStickers.forEach { add(StickerCell.User(it)) }
                },
                currentIcon = currentIcon,
                enabled = enabled,
                selecting = managing,
                selectedIds = selectedIds,
                dimOthers = managing,
                wideImport = userStickers.isEmpty(),
                showHoverDelete = !managing,
                onAdd = onAdd,
                onApplyBuiltIn = onApplyBuiltIn,
                onApplyUser = onApplyUser,
                onClear = onClear,
                onToggleSelect = { sticker ->
                    selectedIds = toggleStickerSelection(selectedIds, sticker.id)
                },
                onLongPressUser = { sticker ->
                    managing = true
                    selectedIds = setOf(sticker.id)
                },
                onHoverDeleteUser = { sticker -> pendingDelete = listOf(sticker) },
            )
            if (managing && userStickers.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("stickerManageBatchBar"),
                ) {
                    StickerDeleteSelectedButton(
                        selectedCount = selectedIds.size,
                        enabled = enabled,
                        onClick = { pendingDelete = userStickers.filter { it.id in selectedIds } },
                    )
                }
            }
            StickerSectionRow(
                text = stringResource(Res.string.sticker_section_builtin),
                tag = "stickerSectionBuiltIn",
                dimmed = managing,
            )
            InspectorCellGridRows(
                cells = BuiltInSticker.entries.map { StickerCell.BuiltIn(it) },
                currentIcon = currentIcon,
                enabled = enabled,
                selecting = false,
                selectedIds = emptySet(),
                dimOthers = managing,
                wideImport = false,
                showHoverDelete = false,
                onAdd = onAdd,
                onApplyBuiltIn = onApplyBuiltIn,
                onApplyUser = onApplyUser,
                onClear = onClear,
                onToggleSelect = {},
                onLongPressUser = {},
                onHoverDeleteUser = {},
            )
        }
    }
    StickerDeleteConfirmDialog(
        targets = pendingDelete,
        onDismiss = { pendingDelete = emptyList() },
        onConfirmDelete = { targets ->
            pendingDelete = emptyList()
            selectedIds = emptySet()
            managing = false
            deleteUsers(targets)
        },
    )
}

/**
 * The inspector body is embedded in the inspector's own `verticalScroll`, so the 4-column grid is
 * laid out as plain chunked rows — a `LazyVerticalGrid` cannot nest in an unbounded scroll.
 */
@Composable
private fun InspectorCellGridRows(
    cells: List<StickerCell>,
    currentIcon: MediaRef,
    enabled: Boolean,
    selecting: Boolean,
    selectedIds: Set<String>,
    /** Selection mode dims everything that is not a user sticker (None / Import / built-ins). */
    dimOthers: Boolean,
    wideImport: Boolean,
    showHoverDelete: Boolean,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onClear: (() -> Unit)?,
    onToggleSelect: (Sticker) -> Unit,
    onLongPressUser: (Sticker) -> Unit,
    onHoverDeleteUser: (Sticker) -> Unit,
) {
    // The wide Import tile eats the rest of its row, so rows are chunked by span, not by count.
    val rows = remember(cells, wideImport) {
        val out = mutableListOf<List<StickerCell>>()
        var row = mutableListOf<StickerCell>()
        var used = 0
        cells.forEach { cell ->
            val span = if (cell is StickerCell.Import && wideImport) WIDE_IMPORT_SPAN else 1
            if (used + span > 4) {
                out.add(row)
                row = mutableListOf()
                used = 0
            }
            row.add(cell)
            used += span
        }
        if (row.isNotEmpty()) out.add(row)
        out
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // One cell size for every row: `weight(2f)` next to `weight(1f)` only splits the two gaps
        // the row actually has, which left the first column narrower than the same column in the
        // full rows below.
        val cellSize = ((maxWidth - GridGap * 3) / 4).coerceAtLeast(0.dp)
        val wideWidth = cellSize * WIDE_IMPORT_SPAN + GridGap * (WIDE_IMPORT_SPAN - 1)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            rows.forEach { rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                    rowCells.forEach { rowCell ->
                        val wide = rowCell is StickerCell.Import && wideImport
                        val user = (rowCell as? StickerCell.User)?.sticker
                        StickerCellTile(
                            cell = rowCell,
                            currentIcon = currentIcon,
                            enabled = enabled,
                            modifier = if (wide) {
                                Modifier.width(wideWidth).height(cellSize)
                            } else {
                                Modifier.size(cellSize)
                            },
                            selecting = selecting && user != null,
                            picked = user != null && user.id in selectedIds,
                            dimmed = user == null && dimOthers,
                            wideImport = wide,
                            showHoverDelete = showHoverDelete,
                            onClear = onClear,
                            onAdd = onAdd,
                            onApplyBuiltIn = onApplyBuiltIn,
                            onApplyUser = onApplyUser,
                            onLongPressUser = onLongPressUser,
                            onToggleUser = onToggleSelect,
                            onHoverDeleteUser = onHoverDeleteUser,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerSectionRow(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    /**
     * Pinned by the caller where the row hosts an action in one mode only — otherwise entering
     * selection mode would drop the row by 8 dp and move every tile under it.
     */
    height: Dp = if (action == null) SectionRowHeight else SectionRowActionHeight,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .then(if (dimmed) Modifier.graphicsLayer { alpha = DIMMED_ALPHA } else Modifier)
                .testTag(tag),
        )
        action?.invoke()
    }
}

/**
 * Borderless, fill-less text action (Manage / Select all / Done) — no pill, no outline.
 * [compact] is the 12 sp / 40 dp form used in section label rows; otherwise 14 sp / 44 dp for the
 * panel header.
 */
@Composable
private fun StickerTextButton(
    text: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
    compact: Boolean = true,
) {
    val accentColor = editorAccentColor()
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        accent -> accentColor
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    }
    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = 48.dp,
                minHeight = if (compact) SectionRowActionHeight else PanelHeaderHeight,
            )
            .clip(StickerTileShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 12.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `n selected` / select all / `Done`, measured by priority instead of by
 * [Arrangement.SpaceBetween]: `Done` is measured first and always keeps its full width, the
 * select-all button gives way next, and the count gives way first (down to nothing). One line
 * each, so a narrow phone at a large font scale neither clips `Done` nor grows the row.
 *
 * Both hosts hand this row a bounded width (`fillMaxWidth`).
 */
@Composable
private fun StickerSelectionHeader(
    count: String,
    countStyle: TextStyle,
    allSelected: Boolean,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onToggleSelectAll: () -> Unit,
    onDone: () -> Unit,
) {
    Layout(
        modifier = modifier,
        contents = listOf(
            {
                Text(
                    text = count,
                    style = countStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("stickerSelectedCount"),
                )
            },
            {
                StickerTextButton(
                    text = stringResource(
                        if (allSelected) {
                            Res.string.sticker_deselect_all
                        } else {
                            Res.string.sticker_select_all
                        },
                    ),
                    tag = "stickerManageSelectAll",
                    enabled = enabled,
                    onClick = onToggleSelectAll,
                    compact = compact,
                )
            },
            {
                StickerTextButton(
                    text = stringResource(Res.string.preview_selection_done),
                    tag = "stickerManageDone",
                    enabled = enabled,
                    onClick = onDone,
                    accent = true,
                    compact = compact,
                )
            },
        ),
    ) { (countContent, selectAllContent, doneContent), constraints ->
        val width = constraints.maxWidth
        fun measure(content: List<Measurable>, room: Int): Placeable? =
            content.firstOrNull()?.measure(
                Constraints(maxWidth = room.coerceAtLeast(0), maxHeight = constraints.maxHeight),
            )
        val done = measure(doneContent, width)
        val selectAll = measure(selectAllContent, width - (done?.width ?: 0))
        val countLabel = measure(
            countContent,
            width - (done?.width ?: 0) - (selectAll?.width ?: 0),
        )
        val height = listOfNotNull(countLabel, selectAll, done)
            .maxOfOrNull { it.height }
            ?.coerceIn(constraints.minHeight, constraints.maxHeight)
            ?: constraints.minHeight
        layout(width, height) {
            var end = width
            done?.let {
                end -= it.width
                it.placeRelative(end, (height - it.height) / 2)
            }
            selectAll?.let {
                end -= it.width
                it.placeRelative(end, (height - it.height) / 2)
            }
            countLabel?.placeRelative(0, (height - countLabel.height) / 2)
        }
    }
}

/** Full-width rectangle destructive action: border only, error-coloured, disabled while n = 0. */
@Composable
private fun StickerDeleteSelectedButton(
    selectedCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val canDelete = enabled && selectedCount > 0
    val error = MaterialTheme.colorScheme.error
    val borderColor = if (canDelete) error.copy(alpha = 0.70f) else Color.White.copy(alpha = 0.12f)
    val labelColor = if (canDelete) error else Color.White.copy(alpha = 0.34f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DeleteFooterHeight)
            .border(1.dp, borderColor, RectangleShape)
            .clickable(enabled = canDelete, onClick = onClick)
            .semantics {
                role = Role.Button
                if (!canDelete) disabled()
            }
            .testTag("stickerManageDeleteSelected"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.sticker_delete_selected, selectedCount),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun StickerDeleteConfirmDialog(
    targets: List<Sticker>,
    onDismiss: () -> Unit,
    onConfirmDelete: (List<Sticker>) -> Unit,
) {
    if (targets.isEmpty()) return
    EwmConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.sticker_delete_title),
        text = stringResource(Res.string.sticker_delete_batch_confirm, targets.size),
        confirmLabel = stringResource(Res.string.template_delete),
        dismissLabel = stringResource(Res.string.tips_cancel_dialog),
        onConfirm = { onConfirmDelete(targets) },
    )
}

private fun toggleStickerSelection(selectedIds: Set<String>, id: String): Set<String> =
    if (id in selectedIds) selectedIds - id else selectedIds + id

private fun reconcileStickerSelection(
    managing: Boolean,
    userStickers: List<Sticker>,
    selectedIds: Set<String>,
): Set<String> {
    if (!managing) return emptySet()
    val validIds = userStickers.map { it.id }.toSet()
    return selectedIds.intersect(validIds)
}

@Composable
private fun StickerCellTile(
    cell: StickerCell,
    currentIcon: MediaRef,
    enabled: Boolean,
    modifier: Modifier,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onClear: (() -> Unit)? = null,
    selecting: Boolean = false,
    picked: Boolean = false,
    dimmed: Boolean = false,
    wideImport: Boolean = false,
    noneTag: String = "stickerClear",
    showHoverDelete: Boolean = false,
    onLongPressUser: ((Sticker) -> Unit)? = null,
    onToggleUser: ((Sticker) -> Unit)? = null,
    onHoverDeleteUser: ((Sticker) -> Unit)? = null,
) {
    when (cell) {
        StickerCell.None -> {
            val noneLabel = stringResource(Res.string.sticker_none)
            val cleared = currentIcon.value.isBlank()
            StickerUtilityTile(
                isSelected = cleared,
                enabled = enabled,
                dimmed = dimmed,
                label = noneLabel,
                modifier = modifier.testTag(noneTag),
                onClick = { onClear?.invoke() },
            ) {
                StickerUtilityGlyph(
                    glyph = StickerGlyph.None,
                    // Dimmed (selection mode) drops the applied highlight, accent glyph included.
                    color = if (cleared && !dimmed) {
                        editorAccentColor()
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f)
                    },
                    modifier = Modifier.size(UtilityIconSize),
                )
            }
        }
        StickerCell.Import -> {
            val importLabel = stringResource(Res.string.sticker_import)
            StickerUtilityTile(
                isSelected = false,
                enabled = enabled,
                dimmed = dimmed,
                label = importLabel,
                role = Role.Button,
                modifier = modifier.testTag("stickerAdd"),
                onClick = onAdd,
            ) {
                if (wideImport) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StickerUtilityGlyph(
                            glyph = StickerGlyph.Import,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                            modifier = Modifier.size(UtilityIconSize),
                        )
                        Text(
                            text = importLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    StickerUtilityGlyph(
                        glyph = StickerGlyph.Import,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                        modifier = Modifier.size(UtilityIconSize),
                    )
                }
            }
        }
        is StickerCell.BuiltIn -> {
            val painter = painterResource(cell.sticker.drawable)
            val builtInLabel = stringResource(cell.sticker.label)
            StickerArtTile(
                isSelected = cell.sticker.matches(currentIcon),
                enabled = enabled,
                dimmed = dimmed,
                label = builtInLabel,
                modifier = modifier.testTag("stickerBuiltIn_${cell.sticker.key}"),
                onClick = { onApplyBuiltIn(cell.sticker, painter.toStickerBitmap()) },
            ) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(BUILT_IN_ART_FRACTION),
                )
            }
        }
        is StickerCell.User -> {
            val sticker = cell.sticker
            StickerArtTile(
                isSelected = !selecting && currentIcon.value.isNotBlank() &&
                    currentIcon.value == sticker.icon.value,
                enabled = enabled,
                dimmed = dimmed,
                modifier = modifier.testTag("stickerUser_${sticker.id}"),
                selecting = selecting,
                picked = picked,
                checkboxTag = "stickerManageSelect_${sticker.id}",
                hoverDeleteTag = if (showHoverDelete) "stickerDelete_${sticker.id}" else null,
                onHoverDelete = if (showHoverDelete && onHoverDeleteUser != null) {
                    { onHoverDeleteUser(sticker) }
                } else {
                    null
                },
                onClick = {
                    if (selecting) onToggleUser?.invoke(sticker) else onApplyUser(sticker)
                },
                onLongClick = if (!selecting && onLongPressUser != null) {
                    { onLongPressUser(sticker) }
                } else {
                    null
                },
                onToggle = { onToggleUser?.invoke(sticker) },
            ) {
                UserStickerArt(
                    sticker = sticker,
                    modifier = Modifier.fillMaxSize(USER_ART_FRACTION),
                )
            }
        }
    }
}

/** None / Import / All: the only tiles that get a well (fill); stickers sit bare on the surface. */
@Composable
private fun StickerUtilityTile(
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    dimmed: Boolean = false,
    label: String? = null,
    /** `None` is one option of the sticker choice group; `Import` and `All` just act. */
    role: Role = Role.RadioButton,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = editorAccentColor()
    val selectedContainer = editorSelectedContainerColor()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val canInteract = enabled && !dimmed
    // Only a choice tile may expose `selected`: on a plain button Android reports that property as
    // checkable, which announced `Import` and `All` as "unchecked".
    val isChoice = role != Role.Button
    // Selection mode draws no applied highlight (spec 1.3): a dimmed tile keeps its idle well,
    // while `selected` semantics still report what is actually applied.
    val highlighted = isSelected && !dimmed
    val fill = when {
        highlighted -> selectedContainer
        hovered && canInteract -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    Box(
        modifier = modifier
            .then(if (dimmed) Modifier.graphicsLayer { alpha = DIMMED_ALPHA } else Modifier)
            .clip(StickerTileShape)
            .background(fill, StickerTileShape)
            .then(if (highlighted) Modifier.border(1.5.dp, accent, StickerTileShape) else Modifier)
            .hoverable(interactionSource = interaction, enabled = canInteract)
            .semantics {
                if (isChoice) selected = isSelected
                if (label != null) contentDescription = label
                this.role = role
                if (!canInteract) disabled()
            }
            .clickable(enabled = canInteract, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Sticker tile: no fill and no border at idle; the art is the affordance. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerArtTile(
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    dimmed: Boolean = false,
    label: String? = null,
    selecting: Boolean = false,
    picked: Boolean = false,
    checkboxTag: String? = null,
    hoverDeleteTag: String? = null,
    onHoverDelete: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = editorAccentColor()
    val selectedContainer = editorSelectedContainerColor()
    val chrome = editorChromeColor()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val canInteract = enabled && !dimmed
    val chosen = if (selecting) picked else isSelected
    // Selection mode draws no applied highlight (spec 1.3): a dimmed tile keeps its idle look
    // (no fill, no border), while `selected` semantics still report what is actually applied.
    val highlighted = chosen && !dimmed
    val fill = when {
        highlighted -> selectedContainer
        hovered && canInteract -> Color.White.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .then(if (dimmed) Modifier.graphicsLayer { alpha = DIMMED_ALPHA } else Modifier)
            .clip(StickerTileShape)
            .background(fill, StickerTileShape)
            .then(
                if (highlighted) Modifier.border(1.5.dp, accent, StickerTileShape) else Modifier,
            )
            .hoverable(interactionSource = interaction, enabled = canInteract)
            .semantics {
                selected = chosen
                if (label != null) contentDescription = label
                // Idle the tiles are one exclusive choice; in selection mode each is its own
                // independent pick.
                role = if (selecting) Role.Checkbox else Role.RadioButton
                if (!canInteract) disabled()
            }
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        enabled = canInteract,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(enabled = canInteract, onClick = onClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (selecting) {
            val checkboxInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(CheckboxSize)
                    .clip(CheckboxShape)
                    .background(
                        if (picked) accent else chrome.copy(alpha = 0.7f),
                        CheckboxShape,
                    )
                    .then(
                        if (picked) {
                            Modifier
                        } else {
                            Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), CheckboxShape)
                        },
                    )
                    .semantics {
                        selected = picked
                        role = Role.Checkbox
                        if (!canInteract) disabled()
                    }
                    .then(
                        if (onToggle != null) {
                            Modifier.clickable(
                                interactionSource = checkboxInteraction,
                                indication = null,
                                enabled = canInteract,
                                onClick = onToggle,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(checkboxTag?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (picked) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save_done),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        } else if (onHoverDelete != null && hovered && canInteract) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(HoverDeleteSize)
                    .background(chrome.copy(alpha = 0.92f), CornerDeleteShape)
                    .border(1.dp, Color.White.copy(alpha = 0.24f), CornerDeleteShape)
                    .clickable(enabled = canInteract, onClick = onHoverDelete)
                    .then(hoverDeleteTag?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close_24dp),
                    contentDescription = stringResource(Res.string.template_delete),
                    modifier = Modifier.size(10.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * User sticker thumbnail with a die-cut white outline, so a dark logo stays visible on dark chrome.
 *
 * [ProductAsyncImage] stays the loader: it owns the placeholder memory-cache key (a recycled
 * `LazyRow` item shows the cached pixels on its first frame instead of blank) and the retry on a
 * failed load. The outline is a draw-time effect on top of it, so there is still one decode.
 */
@Composable
private fun UserStickerArt(
    sticker: Sticker,
    modifier: Modifier,
) {
    // Bumped on every Coil state change. The halo layer holds a *recording* of the content, and a
    // snapshot read inside a draw cache is what re-records it once the thumb actually lands.
    var loadEpoch by remember(sticker.icon.value) { mutableIntStateOf(0) }
    ProductAsyncImage(
        thumb = ProductThumb(sticker.icon, ProductThumb.UI_THUMB_MAX_EDGE),
        contentDescription = null,
        modifier = modifier.stickerHalo { loadEpoch },
        contentScale = ContentScale.Fit,
        onState = { loadEpoch++ },
    )
}

/**
 * 1 dp white outline following the content's alpha silhouette: the content is recorded into a
 * graphics layer and stamped at the eight 1 dp offsets, then drawn untinted on top.
 *
 * ponytail: 8-way offset dilate stands in for feMorphology; a real SDF pass would not show at
 * 56 dp. Raise the ring or go SDF if the outline ever needs to be thicker.
 */
@Composable
private fun Modifier.stickerHalo(loadEpoch: () -> Int): Modifier {
    val layer = rememberGraphicsLayer()
    // Set once per layer: on Android a GraphicsLayer is a RenderNode, so a property changed between
    // two draws of the same frame would retro-apply to both — the content would come out white too.
    remember(layer) { layer.colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.9f)) }
    return drawWithCache {
        loadEpoch()
        layer.record { drawContent() }
        val ring = 1.dp.toPx()
        onDrawWithContent {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    translate(dx * ring, dy * ring) { drawLayer(layer) }
                }
            }
            drawContent()
        }
    }
}

/** 24-grid glyphs from the design prototype: `None` = ⊘, `Import` = +, `All` = a 3 × 3 dot grid. */
@Composable
private fun StickerUtilityGlyph(
    glyph: StickerGlyph,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = 1.6f * unit
        when (glyph) {
            StickerGlyph.None -> {
                drawCircle(
                    color = color,
                    radius = 8.5f * unit,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = stroke),
                )
                drawLine(
                    color = color,
                    start = Offset(6f * unit, 18f * unit),
                    end = Offset(18f * unit, 6f * unit),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            StickerGlyph.Import -> {
                drawLine(
                    color = color,
                    start = Offset(12f * unit, 5f * unit),
                    end = Offset(12f * unit, 19f * unit),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(5f * unit, 12f * unit),
                    end = Offset(19f * unit, 12f * unit),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            StickerGlyph.All -> {
                val dots = listOf(6f, 12f, 18f)
                dots.forEach { x ->
                    dots.forEach { y ->
                        drawCircle(
                            color = color,
                            radius = 1.5f * unit,
                            center = Offset(x * unit, y * unit),
                        )
                    }
                }
            }
        }
    }
}
