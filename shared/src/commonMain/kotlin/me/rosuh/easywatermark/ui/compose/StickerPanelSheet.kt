package me.rosuh.easywatermark.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import me.rosuh.easywatermark.shared.generated.resources.ic_close_24dp
import me.rosuh.easywatermark.shared.generated.resources.ic_save_done
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_camera
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_copyright
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_lock_simple
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_prohibit
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_seal_check
import me.rosuh.easywatermark.shared.generated.resources.ic_sticker_shield_check
import me.rosuh.easywatermark.shared.generated.resources.ic_template_list_item_edit
import me.rosuh.easywatermark.shared.generated.resources.ic_tile_mode
import me.rosuh.easywatermark.shared.generated.resources.preview_selection_done
import me.rosuh.easywatermark.shared.generated.resources.sticker_all_open
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_batch_confirm
import me.rosuh.easywatermark.shared.generated.resources.sticker_delete_selected
import me.rosuh.easywatermark.shared.generated.resources.sticker_deselect_all
import me.rosuh.easywatermark.shared.generated.resources.sticker_import
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_camera
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_copyright
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_lock
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_prohibit
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_seal
import me.rosuh.easywatermark.shared.generated.resources.sticker_label_shield
import me.rosuh.easywatermark.shared.generated.resources.sticker_manage
import me.rosuh.easywatermark.shared.generated.resources.sticker_remove
import me.rosuh.easywatermark.shared.generated.resources.sticker_section_builtin
import me.rosuh.easywatermark.shared.generated.resources.sticker_section_mine
import me.rosuh.easywatermark.shared.generated.resources.sticker_select_all
import me.rosuh.easywatermark.shared.generated.resources.template_delete
import me.rosuh.easywatermark.shared.generated.resources.tips_cancel_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_delete_template
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

/** Phosphor regular marks already credited on the Open Source screen. */
enum class BuiltInSticker(
    val key: String,
    val drawable: DrawableResource,
    val label: StringResource,
) {
    Copyright("copyright", Res.drawable.ic_sticker_copyright, Res.string.sticker_label_copyright),
    Prohibit("prohibit", Res.drawable.ic_sticker_prohibit, Res.string.sticker_label_prohibit),
    Shield("shield", Res.drawable.ic_sticker_shield_check, Res.string.sticker_label_shield),
    Seal("seal", Res.drawable.ic_sticker_seal_check, Res.string.sticker_label_seal),
    Camera("camera", Res.drawable.ic_sticker_camera, Res.string.sticker_label_camera),
    Lock("lock", Res.drawable.ic_sticker_lock_simple, Res.string.sticker_label_lock),
    ;

    fun matches(ref: MediaRef): Boolean {
        val fileName = ref.value.substringAfterLast('/').substringAfterLast('\\')
        return fileName == "icon-builtin-$key.png" || fileName == "icon_builtin_$key.png"
    }

    companion object {
        fun fromIcon(ref: MediaRef): BuiltInSticker? = entries.firstOrNull { it.matches(ref) }
    }
}

fun Painter.toStickerBitmap(sizePx: Int = 256): ImageBitmap {
    val bitmap = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(sizePx.toFloat(), sizePx.toFloat()),
    ) {
        draw(
            size = Size(sizePx.toFloat(), sizePx.toFloat()),
            colorFilter = ColorFilter.tint(Color.White),
        )
    }
    return bitmap
}

/** Bumped every time the Icon control is tapped, including a tap on the already selected chip. */
val LocalStickerSheetRequest = staticCompositionLocalOf { 0 }

private val StickerTileShape = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp)
private val SelectedTagShape = RoundedCornerShape(bottomStart = 2.dp)
private val CornerDeleteTagShape = RoundedCornerShape(bottomEnd = 2.dp)

private sealed interface StickerCell {
    data object Add : StickerCell
    data class BuiltIn(val sticker: BuiltInSticker) : StickerCell
    data class User(val sticker: Sticker) : StickerCell
}

/**
 * Expandable `2.dp` darkroom management & browsing sheet opened from [InlineStickerRail] on compact
 * screens. Groups stickers into `Mine` and `Built-in` when user stickers exist and provides an
 * explicit `Manage` toggle for one-tap deletion alongside live 1-tap switching.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val deleteUsers: (List<Sticker>) -> Unit = onDeleteUsers ?: { list -> list.forEach(onDeleteUser) }
    var pendingDelete by remember { mutableStateOf<Sticker?>(null) }
    var pendingBatchDelete by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(userStickers.isEmpty()) {
        if (userStickers.isEmpty()) managing = false
    }
    LaunchedEffect(managing, userStickers) {
        selectedIds = reconcileStickerSelection(managing, userStickers, selectedIds)
    }
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .then(if (useLargeDialog) Modifier else Modifier.navigationBarsPadding())
                .padding(bottom = 16.dp)
                .testTag("stickerPanelSheet"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.water_mark_mode_image),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!managing && currentIcon.value.isNotBlank() && onClear != null) {
                        StickerRemoveAction(
                            enabled = enabled,
                            tag = "stickerSheetClear",
                            compact = false,
                            onClick = onClear,
                        )
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(Color.White.copy(alpha = 0.12f)),
                        )
                    }
                    val closeInteraction = remember { MutableInteractionSource() }
                    val closeHovered by closeInteraction.collectIsHoveredAsState()
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(StickerTileShape)
                            .background(
                                Color.White.copy(alpha = if (closeHovered) 0.12f else 0.05f),
                                StickerTileShape,
                            )
                            .border(
                                1.dp,
                                Color.White.copy(alpha = if (closeHovered) 0.24f else 0.12f),
                                StickerTileShape,
                            )
                            .hoverable(interactionSource = closeInteraction)
                            .clickable(onClick = onDismiss)
                            .testTag("stickerSheetClose"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close_24dp),
                            contentDescription = stringResource(Res.string.cd_back),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 360.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (userStickers.isNotEmpty()) {
                    item(
                        key = "section_mine",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "section",
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StickerSectionLabel(
                                text = stringResource(Res.string.sticker_section_mine),
                                modifier = Modifier.testTag("stickerSectionMine"),
                            )
                            StickerManageToggleButton(
                                managing = managing,
                                enabled = enabled,
                                compact = false,
                                onClick = { managing = !managing },
                            )
                        }
                    }
                    item(key = "add", contentType = "add") {
                        StickerAddCell(
                            enabled = enabled,
                            dimmed = managing,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            onClick = onAdd,
                        )
                    }
                    items(
                        items = userStickers,
                        key = { sticker -> "user_${sticker.id}" },
                        contentType = { "user" },
                    ) { sticker ->
                        val selected = currentIcon.value.isNotBlank() &&
                            currentIcon.value == sticker.icon.value
                        StickerCellItem(
                            cell = StickerCell.User(sticker),
                            selected = selected,
                            enabled = enabled,
                            tileModifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            builtInIconSize = 26.dp,
                            userIconSize = 32.dp,
                            showHoverDelete = false,
                            manageSelectable = managing,
                            manageSelected = sticker.id in selectedIds,
                            manageSelectTag = "stickerManageSelect_${sticker.id}",
                            onManageToggleSelect = {
                                selectedIds = toggleStickerSelection(selectedIds, sticker.id)
                            },
                            onAdd = onAdd,
                            onApplyBuiltIn = { _, _ -> },
                            onApplyUser = { s ->
                                onApplyUser(s)
                                if (dismissOnSelect) onDismiss()
                            },
                            onRequestDelete = { pendingDelete = it },
                        )
                    }
                    item(
                        key = "section_builtin",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "section",
                    ) {
                        StickerSectionLabel(
                            text = stringResource(Res.string.sticker_section_builtin),
                            dimmed = managing,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .testTag("stickerSectionBuiltIn"),
                        )
                    }
                    items(
                        items = BuiltInSticker.entries,
                        key = { builtIn -> "builtin_${builtIn.name}" },
                        contentType = { "builtin" },
                    ) { builtIn ->
                        StickerCellItem(
                            cell = StickerCell.BuiltIn(builtIn),
                            selected = builtIn.matches(currentIcon),
                            enabled = enabled,
                            tileModifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            builtInIconSize = 26.dp,
                            userIconSize = 32.dp,
                            showHoverDelete = false,
                            dimmed = managing,
                            onAdd = onAdd,
                            onApplyBuiltIn = { b, bmp ->
                                onApplyBuiltIn(b, bmp)
                                if (dismissOnSelect) onDismiss()
                            },
                            onApplyUser = {},
                            onRequestDelete = {},
                        )
                    }
                } else {
                    item(key = "add", contentType = "add") {
                        StickerAddCell(
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            onClick = onAdd,
                        )
                    }
                    items(
                        items = BuiltInSticker.entries,
                        key = { builtIn -> "builtin_${builtIn.name}" },
                        contentType = { "builtin" },
                    ) { builtIn ->
                        StickerCellItem(
                            cell = StickerCell.BuiltIn(builtIn),
                            selected = builtIn.matches(currentIcon),
                            enabled = enabled,
                            tileModifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            builtInIconSize = 26.dp,
                            userIconSize = 32.dp,
                            showHoverDelete = false,
                            onAdd = onAdd,
                            onApplyBuiltIn = { b, bmp ->
                                onApplyBuiltIn(b, bmp)
                                if (dismissOnSelect) onDismiss()
                            },
                            onApplyUser = {},
                            onRequestDelete = {},
                        )
                    }
                }
            }
            if (managing && userStickers.isNotEmpty()) {
                val allSelected = userStickers.all { it.id in selectedIds }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                StickerManageBatchBar(
                    selectedCount = selectedIds.size,
                    allSelected = allSelected,
                    enabled = enabled,
                    compact = false,
                    onToggleSelectAll = {
                        selectedIds = if (allSelected) {
                            emptySet()
                        } else {
                            userStickers.map { it.id }.toSet()
                        }
                    },
                    onDeleteSelected = {
                        pendingBatchDelete = userStickers.filter { it.id in selectedIds }
                    },
                )
            }
        }
    }
    val overlayHost = LocalEditorBottomOverlayHost.current
    if (useLargeDialog) {
        EwmContentDialog(
            onDismissRequest = onDismiss,
            maxWidth = 380.dp,
            scrollContent = false,
        ) { body() }
    } else if (overlayHost != null) {
        val overlayToken = remember { Any() }
        val latestOnDismiss by rememberUpdatedState(onDismiss)
        SideEffect {
            overlayHost.show(
                token = overlayToken,
                onDismiss = { latestOnDismiss() },
            ) {
                DockedStickerOverlaySurface(
                    onDismiss = { latestOnDismiss() },
                    body = body,
                )
            }
        }
        DisposableEffect(overlayHost, overlayToken) {
            onDispose {
                overlayHost.clear(overlayToken)
            }
        }
    } else {
        EwmModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            scrimColor = Color.Transparent,
        ) { body() }
    }
    StickerDeleteConfirmDialog(
        pendingDelete = pendingDelete,
        onDismiss = { pendingDelete = null },
        onConfirmDelete = { sticker ->
            pendingDelete = null
            onDeleteUser(sticker)
        },
    )
    StickerBatchDeleteConfirmDialog(
        pendingBatchDelete = pendingBatchDelete,
        onDismiss = { pendingBatchDelete = emptyList() },
        onConfirmDelete = { targets ->
            selectedIds = emptySet()
            pendingBatchDelete = emptyList()
            deleteUsers(targets)
        },
    )
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
            .ewmTestTagsAsResourceId()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        shape = dockedShape,
        color = EwmTheme.panel.containerColor,
        tonalElevation = EwmTheme.panel.tonalElevation,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
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
 * Displays an inline 4-column sticker grid in dual-pane inspectors ([useLargeDialog] = true) so
 * desktop/tablet previews stay unobstructed, or a quick-pick inline horizontal rail with a pinned
 * expand entry (`stickerExpand`) to open the grouped management [StickerPanelSheet] on phones.
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
    val hasIcon = waterMark.iconUri.value.isNotBlank()
    if (useLargeDialog) {
        InlineStickerInspectorPanel(
            hasIcon = hasIcon,
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
            hasIcon = hasIcon,
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
    hasIcon: Boolean,
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
    var pendingDelete by remember { mutableStateOf<Sticker?>(null) }
    var showSheet by remember { mutableStateOf(false) }
    val motionPolicy = currentMotionPolicy()
    val fadeMs = motionDurationMs(motionPolicy, EwmMotionTokens.optionPanelFadeMs)
    val removeLabel = stringResource(Res.string.sticker_remove)
    val allStickersLabel = stringResource(Res.string.sticker_all_open)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stickerInlineRail"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 12.dp, end = 4.dp)
                .size(width = 44.dp, height = 48.dp)
                .clip(StickerTileShape)
                .clickable(enabled = enabled) { showSheet = true }
                .semantics { contentDescription = allStickersLabel }
                .testTag("stickerExpand"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_tile_mode),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
            )
        }
        Box(
            Modifier
                .width(1.dp)
                .height(28.dp)
                .background(Color.White.copy(alpha = 0.14f)),
        )
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            overscrollEffect = rememberOverscrollEffect(),
        ) {
            item(key = "add", contentType = "add") {
                StickerAddCell(
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                    onClick = onAdd,
                )
            }
            items(
                items = userStickers,
                key = { sticker -> "user_${sticker.id}" },
                contentType = { "user" },
            ) { sticker ->
                val selected = currentIcon.value.isNotBlank() &&
                    currentIcon.value == sticker.icon.value
                StickerCellItem(
                    cell = StickerCell.User(sticker),
                    selected = selected,
                    enabled = enabled,
                    tileModifier = Modifier.size(48.dp),
                    builtInIconSize = 24.dp,
                    userIconSize = 28.dp,
                    showHoverDelete = false,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                    onRequestDelete = { pendingDelete = it },
                )
            }
            items(
                items = BuiltInSticker.entries,
                key = { builtIn -> "builtin_${builtIn.name}" },
                contentType = { "builtin" },
            ) { builtIn ->
                StickerCellItem(
                    cell = StickerCell.BuiltIn(builtIn),
                    selected = builtIn.matches(currentIcon),
                    enabled = enabled,
                    tileModifier = Modifier.size(48.dp),
                    builtInIconSize = 24.dp,
                    userIconSize = 28.dp,
                    showHoverDelete = false,
                    onAdd = onAdd,
                    onApplyBuiltIn = onApplyBuiltIn,
                    onApplyUser = onApplyUser,
                    onRequestDelete = { pendingDelete = it },
                )
            }
        }
        AnimatedVisibility(
            visible = hasIcon && onClear != null,
            enter = fadeIn(tween(fadeMs)),
            exit = fadeOut(tween(fadeMs)),
        ) {
            if (onClear != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(Color.White.copy(alpha = 0.14f)),
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp, end = 12.dp)
                            .size(width = 44.dp, height = 48.dp)
                            .clip(StickerTileShape)
                            .clickable(enabled = enabled, onClick = onClear)
                            .semantics { contentDescription = removeLabel }
                            .testTag("stickerClear"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
    if (showSheet) {
        StickerPanelSheet(
            userStickers = userStickers,
            enabled = enabled,
            useLargeDialog = false,
            currentIcon = currentIcon,
            onDismiss = { showSheet = false },
            onAdd = onAdd,
            onApplyBuiltIn = onApplyBuiltIn,
            onApplyUser = onApplyUser,
            onDeleteUser = onDeleteUser,
            onClear = onClear,
            dismissOnSelect = false,
            onDeleteUsers = onDeleteUsers,
        )
    }
    StickerDeleteConfirmDialog(
        pendingDelete = pendingDelete,
        onDismiss = { pendingDelete = null },
        onConfirmDelete = { sticker ->
            pendingDelete = null
            onDeleteUser(sticker)
        },
    )
}

@Composable
private fun InlineStickerInspectorPanel(
    hasIcon: Boolean,
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
    var pendingDelete by remember { mutableStateOf<Sticker?>(null) }
    var pendingBatchDelete by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(userStickers.isEmpty()) {
        if (userStickers.isEmpty()) managing = false
    }
    LaunchedEffect(managing, userStickers) {
        selectedIds = reconcileStickerSelection(managing, userStickers, selectedIds)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stickerInlinePanel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.water_mark_mode_image),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("stickerInlineTitle"),
            )
            if (!managing && hasIcon && onClear != null) {
                StickerRemoveAction(
                    enabled = enabled,
                    tag = "stickerClear",
                    compact = true,
                    onClick = onClear,
                )
            }
        }
        if (userStickers.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StickerSectionLabel(
                    text = stringResource(Res.string.sticker_section_mine),
                    modifier = Modifier.testTag("stickerSectionMine"),
                )
                StickerManageToggleButton(
                    managing = managing,
                    enabled = enabled,
                    compact = true,
                    onClick = { managing = !managing },
                )
            }
            InspectorCellGridRows(
                cells = buildList {
                    add(StickerCell.Add)
                    userStickers.forEach { add(StickerCell.User(it)) }
                },
                currentIcon = currentIcon,
                enabled = enabled,
                manageMode = managing,
                selectedIds = selectedIds,
                dimAdd = managing,
                dimTiles = false,
                showHoverDelete = !managing,
                onAdd = onAdd,
                onApplyBuiltIn = onApplyBuiltIn,
                onApplyUser = onApplyUser,
                onToggleSelect = { sticker ->
                    selectedIds = toggleStickerSelection(selectedIds, sticker.id)
                },
                onRequestDelete = { pendingDelete = it },
            )
            if (managing) {
                val allSelected = userStickers.all { it.id in selectedIds }
                StickerManageBatchBar(
                    selectedCount = selectedIds.size,
                    allSelected = allSelected,
                    enabled = enabled,
                    compact = true,
                    onToggleSelectAll = {
                        selectedIds = if (allSelected) {
                            emptySet()
                        } else {
                            userStickers.map { it.id }.toSet()
                        }
                    },
                    onDeleteSelected = {
                        pendingBatchDelete = userStickers.filter { it.id in selectedIds }
                    },
                )
            }
            StickerSectionLabel(
                text = stringResource(Res.string.sticker_section_builtin),
                dimmed = managing,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("stickerSectionBuiltIn"),
            )
            InspectorCellGridRows(
                cells = BuiltInSticker.entries.map { StickerCell.BuiltIn(it) },
                currentIcon = currentIcon,
                enabled = enabled,
                manageMode = false,
                selectedIds = emptySet(),
                dimAdd = false,
                dimTiles = managing,
                showHoverDelete = false,
                onAdd = onAdd,
                onApplyBuiltIn = onApplyBuiltIn,
                onApplyUser = onApplyUser,
                onToggleSelect = {},
                onRequestDelete = { pendingDelete = it },
            )
        } else {
            InspectorCellGridRows(
                cells = buildList {
                    add(StickerCell.Add)
                    BuiltInSticker.entries.forEach { add(StickerCell.BuiltIn(it)) }
                },
                currentIcon = currentIcon,
                enabled = enabled,
                manageMode = false,
                selectedIds = emptySet(),
                dimAdd = false,
                dimTiles = false,
                showHoverDelete = false,
                onAdd = onAdd,
                onApplyBuiltIn = onApplyBuiltIn,
                onApplyUser = onApplyUser,
                onToggleSelect = {},
                onRequestDelete = { pendingDelete = it },
            )
        }
    }
    StickerDeleteConfirmDialog(
        pendingDelete = pendingDelete,
        onDismiss = { pendingDelete = null },
        onConfirmDelete = { sticker ->
            pendingDelete = null
            onDeleteUser(sticker)
        },
    )
    StickerBatchDeleteConfirmDialog(
        pendingBatchDelete = pendingBatchDelete,
        onDismiss = { pendingBatchDelete = emptyList() },
        onConfirmDelete = { targets ->
            selectedIds = emptySet()
            pendingBatchDelete = emptyList()
            deleteUsers(targets)
        },
    )
}

@Composable
private fun InspectorCellGridRows(
    cells: List<StickerCell>,
    currentIcon: MediaRef,
    enabled: Boolean,
    manageMode: Boolean,
    selectedIds: Set<String>,
    dimAdd: Boolean,
    dimTiles: Boolean,
    showHoverDelete: Boolean,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onToggleSelect: (Sticker) -> Unit,
    onRequestDelete: (Sticker) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cells.chunked(4).forEach { rowCells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowCells.forEach { cell ->
                    val cellSelected = when (cell) {
                        StickerCell.Add -> false
                        is StickerCell.BuiltIn -> cell.sticker.matches(currentIcon)
                        is StickerCell.User -> currentIcon.value.isNotBlank() &&
                            currentIcon.value == cell.sticker.icon.value
                    }
                    val userSticker = (cell as? StickerCell.User)?.sticker
                    StickerCellItem(
                        cell = cell,
                        selected = cellSelected,
                        enabled = enabled,
                        tileModifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        builtInIconSize = 26.dp,
                        userIconSize = 30.dp,
                        showHoverDelete = showHoverDelete,
                        manageSelectable = manageMode && userSticker != null,
                        manageSelected = userSticker != null && userSticker.id in selectedIds,
                        manageSelectTag = userSticker?.let { "stickerManageSelect_${it.id}" },
                        onManageToggleSelect = userSticker?.let { { onToggleSelect(it) } },
                        dimmed = if (cell is StickerCell.Add) dimAdd else dimTiles,
                        onAdd = onAdd,
                        onApplyBuiltIn = onApplyBuiltIn,
                        onApplyUser = onApplyUser,
                        onRequestDelete = onRequestDelete,
                    )
                }
                repeat(4 - rowCells.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StickerSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        letterSpacing = 0.8.sp,
        modifier = modifier.then(
            if (dimmed) Modifier.graphicsLayer { alpha = 0.42f } else Modifier,
        ),
    )
}

@Composable
private fun StickerManageToggleButton(
    managing: Boolean,
    enabled: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = editorAccentColor()
    val selectedContainer = editorSelectedContainerColor()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        managing -> selectedContainer
        hovered && enabled -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val borderColor = when {
        managing -> accent
        hovered && enabled -> Color.White.copy(alpha = 0.26f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val tint = when {
        managing -> accent
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        hovered -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
    }
    Box(
        modifier = Modifier
            .height(if (compact) 26.dp else 28.dp)
            .clip(StickerTileShape)
            .background(fill, StickerTileShape)
            .border(1.dp, borderColor, StickerTileShape)
            .hoverable(interactionSource = interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 9.dp)
            .testTag("stickerManageToggle"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (managing) {
                        Res.drawable.ic_save_done
                    } else {
                        Res.drawable.ic_template_list_item_edit
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = tint,
            )
            Text(
                text = stringResource(if (managing) Res.string.preview_selection_done else Res.string.sticker_manage),
                fontSize = 12.sp,
                fontWeight = if (managing) FontWeight.SemiBold else FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StickerRemoveAction(
    enabled: Boolean,
    tag: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill = if (hovered && enabled) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }
    val borderColor = if (hovered && enabled) {
        Color.White.copy(alpha = 0.26f)
    } else {
        Color.White.copy(alpha = 0.14f)
    }
    val tint = MaterialTheme.colorScheme.onSurface.copy(
        alpha = when {
            !enabled -> 0.38f
            hovered -> 1f
            else -> 0.88f
        },
    )
    Box(
        modifier = Modifier
            .height(if (compact) 26.dp else 32.dp)
            .clip(StickerTileShape)
            .background(fill, StickerTileShape)
            .border(1.dp, borderColor, StickerTileShape)
            .hoverable(interactionSource = interaction, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StickerClearGlyph(
                color = tint,
                modifier = Modifier.size(if (compact) 11.dp else 12.dp),
            )
            Text(
                text = stringResource(Res.string.sticker_remove),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StickerClearGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeW = 1.25.dp.toPx()
        val inset = strokeW / 2f
        val radius = EwmTheme.shapes.chipRadiusDp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeW, size.height - strokeW),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = strokeW),
        )
        val diagInset = strokeW + 1.5.dp.toPx()
        drawLine(
            color = color,
            start = Offset(diagInset, size.height - diagInset),
            end = Offset(size.width - diagInset, diagInset),
            strokeWidth = strokeW,
            cap = StrokeCap.Square,
        )
    }
}

@Composable
private fun StickerDeleteConfirmDialog(
    pendingDelete: Sticker?,
    onDismiss: () -> Unit,
    onConfirmDelete: (Sticker) -> Unit,
) {
    pendingDelete?.let { sticker ->
        EwmConfirmDialog(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.template_delete),
            text = stringResource(Res.string.tips_delete_template),
            confirmLabel = stringResource(Res.string.tips_confirm_dialog),
            dismissLabel = stringResource(Res.string.tips_cancel_dialog),
            onConfirm = { onConfirmDelete(sticker) },
        )
    }
}

@Composable
private fun StickerBatchDeleteConfirmDialog(
    pendingBatchDelete: List<Sticker>,
    onDismiss: () -> Unit,
    onConfirmDelete: (List<Sticker>) -> Unit,
) {
    if (pendingBatchDelete.isNotEmpty()) {
        EwmConfirmDialog(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.template_delete),
            text = stringResource(Res.string.sticker_delete_batch_confirm, pendingBatchDelete.size),
            confirmLabel = stringResource(Res.string.tips_confirm_dialog),
            dismissLabel = stringResource(Res.string.tips_cancel_dialog),
            onConfirm = { onConfirmDelete(pendingBatchDelete) },
        )
    }
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
private fun StickerManageBatchBar(
    selectedCount: Int,
    allSelected: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val accent = editorAccentColor()
    val selectedContainer = editorSelectedContainerColor()
    val barHeight = if (compact) 30.dp else 36.dp
    val selectAllInteraction = remember { MutableInteractionSource() }
    val selectAllHovered by selectAllInteraction.collectIsHoveredAsState()
    val selectAllFill = when {
        allSelected -> selectedContainer
        selectAllHovered && enabled -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val selectAllBorder = when {
        allSelected -> accent
        selectAllHovered && enabled -> Color.White.copy(alpha = 0.26f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val selectAllTint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        allSelected -> accent
        selectAllHovered -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    }

    val canDelete = enabled && selectedCount > 0
    val deleteInteraction = remember { MutableInteractionSource() }
    val deleteHovered by deleteInteraction.collectIsHoveredAsState()
    val deleteFill = if (canDelete) {
        MaterialTheme.colorScheme.error.copy(alpha = if (deleteHovered) 0.24f else 0.16f)
    } else {
        Color.White.copy(alpha = 0.04f)
    }
    val deleteBorder = if (canDelete) {
        MaterialTheme.colorScheme.error.copy(alpha = if (deleteHovered) 0.90f else 0.70f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }
    val deleteTint = if (canDelete) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 2.dp else 8.dp)
            .testTag("stickerManageBatchBar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(barHeight)
                .clip(StickerTileShape)
                .background(selectAllFill, StickerTileShape)
                .border(1.dp, selectAllBorder, StickerTileShape)
                .hoverable(interactionSource = selectAllInteraction, enabled = enabled)
                .clickable(enabled = enabled, onClick = onToggleSelectAll)
                .padding(horizontal = 10.dp)
                .testTag("stickerManageSelectAll"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (allSelected) Res.string.sticker_deselect_all else Res.string.sticker_select_all,
                ),
                fontSize = 12.sp,
                fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = selectAllTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(barHeight)
                .clip(StickerTileShape)
                .background(deleteFill, StickerTileShape)
                .border(1.dp, deleteBorder, StickerTileShape)
                .hoverable(interactionSource = deleteInteraction, enabled = canDelete)
                .clickable(enabled = canDelete, onClick = onDeleteSelected)
                .padding(horizontal = 10.dp)
                .testTag("stickerManageDeleteSelected"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(Res.string.sticker_delete_selected, selectedCount),
                fontSize = 12.sp,
                fontWeight = if (canDelete) FontWeight.SemiBold else FontWeight.Medium,
                color = deleteTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StickerCellItem(
    cell: StickerCell,
    selected: Boolean,
    enabled: Boolean,
    tileModifier: Modifier,
    builtInIconSize: Dp,
    userIconSize: Dp,
    showHoverDelete: Boolean,
    manageSelectable: Boolean = false,
    manageSelected: Boolean = false,
    manageSelectTag: String? = null,
    onManageToggleSelect: (() -> Unit)? = null,
    dimmed: Boolean = false,
    onAdd: () -> Unit,
    onApplyBuiltIn: (BuiltInSticker, ImageBitmap) -> Unit,
    onApplyUser: (Sticker) -> Unit,
    onRequestDelete: (Sticker) -> Unit,
) {
    val accent = editorAccentColor()
    when (cell) {
        StickerCell.Add -> {
            StickerAddCell(
                enabled = enabled,
                dimmed = dimmed,
                modifier = tileModifier,
                onClick = onAdd,
            )
        }
        is StickerCell.BuiltIn -> {
            val painter = painterResource(cell.sticker.drawable)
            val label = stringResource(cell.sticker.label)
            StickerGridCell(
                selected = selected,
                enabled = enabled,
                dimmed = dimmed,
                modifier = tileModifier.testTag("stickerBuiltIn_${cell.sticker.key}"),
                onClick = { onApplyBuiltIn(cell.sticker, painter.toStickerBitmap()) },
            ) {
                Icon(
                    painter = painter,
                    contentDescription = label,
                    modifier = Modifier.size(builtInIconSize),
                    tint = if (selected) {
                        accent
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    },
                )
            }
        }
        is StickerCell.User -> {
            StickerGridCell(
                selected = selected,
                enabled = enabled,
                dimmed = dimmed,
                manageSelectable = manageSelectable,
                manageSelected = manageSelected,
                manageSelectTag = manageSelectTag,
                onManageToggleSelect = onManageToggleSelect,
                modifier = tileModifier.testTag("stickerUser_${cell.sticker.id}"),
                onClick = { onApplyUser(cell.sticker) },
                onLongClick = { onRequestDelete(cell.sticker) },
                hoverDeleteTag = if (showHoverDelete) "stickerDelete_${cell.sticker.id}" else null,
                onHoverDelete = if (showHoverDelete) {
                    { onRequestDelete(cell.sticker) }
                } else {
                    null
                },
            ) {
                ProductAsyncImage(
                    thumb = ProductThumb(
                        ref = cell.sticker.icon,
                        maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(userIconSize),
                )
            }
        }
    }
}

@Composable
private fun StickerAddCell(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    val importLabel = stringResource(Res.string.sticker_import)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val accent = editorAccentColor()
    val canInteract = enabled && !dimmed
    val borderColor = if (hovered && canInteract) {
        accent
    } else {
        Color.White.copy(alpha = 0.24f)
    }
    val plusColor = if (hovered && canInteract) {
        accent
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    }
    Box(
        modifier = modifier
            .then(if (dimmed) Modifier.graphicsLayer { alpha = 0.42f } else Modifier)
            .clip(StickerTileShape)
            .background(Color.White.copy(alpha = 0.03f))
            .hoverable(interactionSource = interactionSource, enabled = canInteract)
            .clickable(enabled = canInteract, onClick = onClick)
            .semantics { contentDescription = importLabel }
            .testTag("stickerAdd"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val strokeW = 1.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)
            val radius = EwmTheme.shapes.chipRadiusDp.toPx()
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(strokeW / 2f, strokeW / 2f),
                size = Size(size.width - strokeW, size.height - strokeW),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = strokeW, pathEffect = dash),
            )
            val plusHalf = 7.dp.toPx()
            val plusStroke = 1.5.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawLine(
                color = plusColor,
                start = Offset(cx - plusHalf, cy),
                end = Offset(cx + plusHalf, cy),
                strokeWidth = plusStroke,
                cap = StrokeCap.Square,
            )
            drawLine(
                color = plusColor,
                start = Offset(cx, cy - plusHalf),
                end = Offset(cx, cy + plusHalf),
                strokeWidth = plusStroke,
                cap = StrokeCap.Square,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerGridCell(
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    manageSelectable: Boolean = false,
    manageSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    hoverDeleteTag: String? = null,
    onHoverDelete: (() -> Unit)? = null,
    manageSelectTag: String? = null,
    onManageToggleSelect: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val accent = editorAccentColor()
    val selectedContainer = editorSelectedContainerColor()
    val chromeColor = editorChromeColor()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val canInteract = enabled && !dimmed
    val manageToggle = if (manageSelectable) onManageToggleSelect else null
    val fill = when {
        manageSelectable && manageSelected -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        manageSelectable -> Color.White.copy(alpha = 0.06f)
        selected -> selectedContainer
        hovered && canInteract -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.05f)
    }
    val borderModifier = when {
        manageSelectable && manageSelected -> Modifier.border(
            1.5.dp,
            MaterialTheme.colorScheme.error,
            StickerTileShape,
        )
        manageSelectable -> Modifier.border(
            1.dp,
            Color.White.copy(alpha = if (hovered && canInteract) 0.28f else 0.14f),
            StickerTileShape,
        )
        selected -> Modifier.border(1.5.dp, accent, StickerTileShape)
        hovered && canInteract -> Modifier.border(1.dp, Color.White.copy(alpha = 0.28f), StickerTileShape)
        else -> Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), StickerTileShape)
    }
    val primaryClick = manageToggle ?: onClick
    Box(
        modifier = modifier
            .then(if (dimmed) Modifier.graphicsLayer { alpha = 0.42f } else Modifier)
            .clip(StickerTileShape)
            .background(fill)
            .then(borderModifier)
            .hoverable(interactionSource = interactionSource, enabled = canInteract)
            .semantics { this.selected = if (manageSelectable) manageSelected else selected }
            .then(
                if (onLongClick != null && !manageSelectable) {
                    Modifier.combinedClickable(
                        enabled = canInteract,
                        onClick = primaryClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(enabled = canInteract, onClick = primaryClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (manageSelectable) {
            val badgeInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(StickerTileShape)
                    .background(
                        if (manageSelected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            chromeColor.copy(alpha = 0.72f)
                        },
                        StickerTileShape,
                    )
                    .then(
                        if (!manageSelected) {
                            Modifier.border(1.dp, Color.White.copy(alpha = 0.45f), StickerTileShape)
                        } else {
                            Modifier
                        },
                    )
                    .semantics { this.selected = manageSelected }
                    .then(
                        if (manageToggle != null) {
                            Modifier.clickable(
                                interactionSource = badgeInteraction,
                                indication = null,
                                enabled = canInteract,
                                onClick = manageToggle,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(manageSelectTag?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (manageSelected) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save_done),
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = Color.White,
                    )
                }
            }
        } else if (onHoverDelete != null && hovered && canInteract) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(16.dp)
                    .background(chromeColor.copy(alpha = 0.90f), CornerDeleteTagShape)
                    .border(0.75.dp, Color.White.copy(alpha = 0.22f), CornerDeleteTagShape)
                    .clickable(enabled = canInteract, onClick = onHoverDelete)
                    .then(hoverDeleteTag?.let { Modifier.testTag(it) } ?: Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_close_24dp),
                    contentDescription = stringResource(Res.string.template_delete),
                    modifier = Modifier.size(9.dp),
                    tint = Color.White,
                )
            }
        }
        if (selected && !manageSelectable) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .background(accent, SelectedTagShape)
                    .testTag("stickerSelectedBadge"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_save_done),
                    contentDescription = null,
                    modifier = Modifier.size(9.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
