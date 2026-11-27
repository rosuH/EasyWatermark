package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import me.rosuh.easywatermark.ui.compose.WatermarkTextEditSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.WatermarkMode
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_navigate_up
import me.rosuh.easywatermark.shared.generated.resources.cd_save
import me.rosuh.easywatermark.shared.generated.resources.ios_import_preparing
import me.rosuh.easywatermark.render.PreviewResolutionPolicy
import me.rosuh.easywatermark.shared.generated.resources.tips_low_resolution_source
import me.rosuh.easywatermark.shared.generated.resources.tips_no_image_selected
import org.jetbrains.compose.resources.stringResource

/**
 * Shared product editor screen. S-i18n-2: chrome labels from [Res]; hosts inject painters + slots.
 *
 * ADR-0026 layout (owner 2026-08-10: drop three-zone left rail):
 * - Compact / Medium: vertical stack (preview → filmstrip → bottom controls)
 * - Expanded / Wide (≥800, including ≥1440): supporting-pane A — main (preview + filmstrip) | form inspector
 * Session switching is filmstrip-only; no left session library rail.
 * Filmstrip always under the primary preview (F1), never in the inspector rail.
 */
internal class EditorBottomOverlayHostState {
    private var ownerToken: Any? = null
    private var onDismissHandler: (() -> Unit)? = null
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    fun show(
        token: Any,
        onDismiss: (() -> Unit)? = null,
        overlay: @Composable () -> Unit,
    ) {
        ownerToken = token
        onDismissHandler = onDismiss
        content = overlay
    }

    fun dismiss(): Boolean {
        val handler = onDismissHandler ?: return false
        handler()
        return true
    }

    fun clear(token: Any) {
        if (ownerToken === token) {
            ownerToken = null
            onDismissHandler = null
            content = null
        }
    }
}

internal val LocalEditorBottomOverlayHost = staticCompositionLocalOf<EditorBottomOverlayHostState?> { null }

/** C1: compact bottom overlay never grows past this, however tall the window is (Dp). */
internal const val EDITOR_BOTTOM_OVERLAY_MAX_DP: Float = 440f

/** C1: preferred compact bottom overlay height as a fraction of the editor root height. */
internal const val EDITOR_BOTTOM_OVERLAY_ROOT_FRACTION: Float = 0.48f

/**
 * C1 — fixed height of the compact bottom overlay, in px.
 *
 * [bottomChromePx] is the filmstrip top → root bottom distance (filmstrip + bottom controls +
 * bottom inset): the overlay must at least cover the chrome it is docked over. Beyond that it
 * takes [EDITOR_BOTTOM_OVERLAY_ROOT_FRACTION] of the root, capped at [maxOverlayPx].
 */
internal fun editorBottomOverlayHeightPx(
    rootHeightPx: Int,
    bottomChromePx: Int,
    maxOverlayPx: Int,
): Int {
    if (rootHeightPx <= 0) return bottomChromePx.coerceAtLeast(0)
    val preferred = minOf(
        (rootHeightPx * EDITOR_BOTTOM_OVERLAY_ROOT_FRACTION).roundToInt(),
        maxOverlayPx,
    )
    return maxOf(bottomChromePx, preferred).coerceIn(0, rootHeightPx)
}

data class EditorUiIcons(
    val back: Painter,
    val addMoreImages: Painter,
    val save: Painter,
    val about: Painter,
    val templateList: Painter?,
    val templateEdit: Painter? = null,
    val templateDelete: Painter? = null,
)

@Composable
fun EditorScreen(
    /**
     * Display list for filmstrip (immutable projection — no export jobState/result vars).
     * Prefer host-side UI projection at the boundary.
     */
    imageList: List<ImageInfoUi>,
    waterMark: WaterMark,
    selectedImage: ImageInfoUi?,
    /**
     * Templates for the sheet only. Pass [emptyList] until the sheet opens when the host
     * can defer collection (Android P2); sheet content still receives a live list when open.
     */
    templates: List<Template>,
    icons: EditorUiIcons,
    preview: @Composable (Modifier) -> Unit,
    thumbnail: @Composable (image: ImageInfoUi, contentDescription: String, modifier: Modifier) -> Unit,
    optionItem: @Composable (spec: EditorOptionSpec, selected: Boolean) -> Unit,
    colorOption: @Composable (
        modifier: Modifier,
        waterMark: WaterMark,
        onColor: (Int) -> Unit,
    ) -> Unit,
    iconOption: @Composable (
        modifier: Modifier,
        waterMark: WaterMark,
        onIcon: (MediaRef) -> Unit,
    ) -> Unit,
    onBack: () -> Unit,
    onAddMoreImages: () -> Unit,
    onShowSaveDialog: () -> Unit,
    onGoAboutScreen: () -> Unit,
    onImageSelected: (ImageInfoUi) -> Unit,
    onConfigChange: (WatermarkConfigChange) -> Unit,
    onUseTemplate: (Template) -> Unit,
    onAddTemplate: (String) -> Unit,
    onUpdateTemplate: (Template) -> Unit,
    onDeleteTemplate: (Template) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * ADR-0026 layout class from host window size. Default [EditorLayoutClass.Compact] keeps
     * phone binary-compatible call sites.
     */
    layoutClass: EditorLayoutClass = EditorLayoutClass.Compact,
    /** Optional: host learns when template sheet opens/closes (defer template Flow collect). */
    onTemplateSheetVisibilityChange: (Boolean) -> Unit = {},
    /**
     * Expanded/Wide form inspector initial tab (0 Content / 1 Style / 2 Layout).
     * Desktop E2E uses `-Dewm.desktop.inspectorTab`. Ignored on Compact/Medium.
     */
    initialInspectorTab: Int = 0,
    /** Phone bottom chrome tab (0 Content / 1 Style / 2 Layout). Store-capture hook. */
    forcedBottomTab: Int = 0,
    /** Option index inside [forcedBottomTab]. Store-capture hook. */
    forcedOptionIndex: Int = 0,
    /** Bump to open the template sheet. Store-capture hook. */
    openTemplateSheetRequest: Int = 0,
    fontPanelState: FontPanelUiState = FontPanelUiState(),
    onFontPanelEvent: (FontPanelEvent) -> Unit = {},
    openFontSheetRequest: Int = 0,
    onClearWatermark: () -> Unit = {},
    overlayBackHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    var showPreviewTextEditor by remember { mutableStateOf(false) }
    val progressiveSlots = LocalEditorProgressiveSlotPresentation.current
    val selected = selectedImage ?: imageList.firstOrNull()
    val hasProgressiveSlots = progressiveSlots?.state?.slots?.isNotEmpty() == true
    val hasPreviewContent = selected != null || hasProgressiveSlots
    val emptyPreview = if (hasProgressiveSlots) {
        stringResource(Res.string.ios_import_preparing)
    } else {
        stringResource(Res.string.tips_no_image_selected)
    }
    val lowResolutionHint = selected?.takeIf {
        PreviewResolutionPolicy.isLowResolutionSource(it.width, it.height)
    }?.let { info ->
        stringResource(Res.string.tips_low_resolution_source, info.width, info.height)
    }
    val backCd = stringResource(Res.string.cd_navigate_up)
    val addMoreCd = stringResource(Res.string.cd_add_more_images)
    val saveCd = stringResource(Res.string.cd_save)
    val aboutCd = stringResource(Res.string.about_title_about)

    val layoutTag = when (layoutClass) {
        EditorLayoutClass.Compact -> "editorLayoutCompact"
        EditorLayoutClass.Medium -> "editorLayoutMedium"
        EditorLayoutClass.Expanded -> "editorLayoutExpanded"
        EditorLayoutClass.Wide -> "editorLayoutWide"
    }
    val dualOrWide = layoutClass == EditorLayoutClass.Expanded || layoutClass == EditorLayoutClass.Wide
    // Editor → About path may skip Launch; warm About bitmaps after first editor frame.
    var warmAbout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(500)
        warmAbout = true
    }
    if (warmAbout) {
        SharedProductDrawables.warmAboutResources()
    }

    EditorTemplateSheetHost(
        templates = templates,
        editIcon = icons.templateEdit,
        deleteIcon = icons.templateDelete,
        useLargeDialog = usesFormInspectorPath(layoutClass),
        newTemplateInitialText = waterMark.text,
        onUse = onUseTemplate,
        onAdd = onAddTemplate,
        onUpdate = onUpdateTemplate,
        onDelete = onDeleteTemplate,
        onSheetVisibilityChange = onTemplateSheetVisibilityChange,
        openRequest = openTemplateSheetRequest,
    ) { showTemplateSheet ->
    EditorFontSheetHost(
        state = fontPanelState,
        onEvent = onFontPanelEvent,
        useLargeDialog = usesLargeScreenDialog(layoutClass),
        sampleFamilies = fontPanelState.sampleFamilies,
        onVisibilityChange = { visible ->
            if (visible) onFontPanelEvent(FontPanelEvent.Open)
        },
        openRequest = openFontSheetRequest,
    ) { showFontSheet ->
        if (showPreviewTextEditor) {
            WatermarkTextEditSheet(
                initialText = waterMark.text,
                templateIcon = icons.templateList,
                enabled = true,
                onConfirm = { onConfigChange(WatermarkConfigChange.Text(it)); showPreviewTextEditor = false },
                onDismiss = { showPreviewTextEditor = false },
                onGoTemplateList = { showPreviewTextEditor = false; showTemplateSheet() },
            )
        }
        val bottomOverlayHost = remember { EditorBottomOverlayHostState() }
        if (!dualOrWide) {
            overlayBackHandler(bottomOverlayHost.content != null) {
                bottomOverlayHost.dismiss()
            }
        }
        // C1 — compact overlay height + preview refit. Measurement flows one way only:
        // root height + bottom chrome height → overlay height → preview *child* height.
        // The preview keeps its full weighted slot (only the tagged node inside it shrinks), so
        // the filmstrip / bottom controls never move and cannot feed a new chrome height back in.
        val density = LocalDensity.current
        var rootHeightPx by remember { mutableIntStateOf(0) }
        var bottomChromeContentPx by remember { mutableIntStateOf(0) }
        // Filmstrip top → root bottom. The chrome sits inside [safeDrawingPadding], the overlay does not.
        val bottomChromePx = bottomChromeContentPx + WindowInsets.safeDrawing.getBottom(density)
        val overlayMaxPx = with(density) { EDITOR_BOTTOM_OVERLAY_MAX_DP.dp.roundToPx() }
        val motionPolicy = currentMotionPolicy()
        val overlaySlideMs = motionDurationMs(motionPolicy, EwmTheme.motion.optionPanelSlideMs)
        val overlayFadeMs = motionDurationMs(motionPolicy, EwmTheme.motion.optionPanelFadeMs)
        val overlayBody = bottomOverlayHost.content
        // The owner clears the host as it leaves composition; keep the last body so the exit
        // transition still has something to draw.
        val retainedOverlayBody = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
        if (overlayBody != null) {
            retainedOverlayBody.value = overlayBody
        }
        // How much the preview gives up to the overlay. `.value` is read inside `layout`, so the
        // refit animation invalidates layout only — it never recomposes the editor.
        val previewRefitPx = animateIntAsState(
            targetValue = if (overlayBody == null) {
                0
            } else {
                (editorBottomOverlayHeightPx(rootHeightPx, bottomChromePx, overlayMaxPx) - bottomChromePx)
                    .coerceAtLeast(0)
            },
            animationSpec = tween(overlaySlideMs, easing = FastOutSlowInEasing),
            label = "editorPreviewRefit",
        )
        CompositionLocalProvider(
            LocalPreviewInteraction provides PreviewInteraction(
                selected?.uri?.value.orEmpty(),
                waterMark,
                onConfigChange,
                { showPreviewTextEditor = true },
                onClear = {
                    if (waterMark.markMode == WatermarkMode.Image) onClearWatermark()
                    else onConfigChange(WatermarkConfigChange.Text(""))
                },
            ),
            LocalEditorBottomOverlayHost provides bottomOverlayHost,
        ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("sharedComposeEditorScreen"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { rootHeightPx = it.height },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .testTag(layoutTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EditorTopBar(
                        backIcon = icons.back,
                        addMoreImagesIcon = icons.addMoreImages,
                        saveIcon = icons.save,
                        aboutIcon = icons.about,
                        backContentDescription = backCd,
                        addMoreImagesContentDescription = addMoreCd,
                        saveContentDescription = saveCd,
                        aboutContentDescription = aboutCd,
                        modifier = Modifier.fillMaxWidth(),
                        onBack = onBack,
                        onAddMoreImages = onAddMoreImages,
                        onShowSaveDialog = onShowSaveDialog,
                        onGoAboutScreen = onGoAboutScreen,
                    )

                    if (dualOrWide) {
                        // Supporting-pane A for Expanded and Wide: preview+filmstrip | form inspector.
                        // Wide no longer mounts a left session library (owner 2026-08-10).
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth()
                                .testTag(
                                    if (layoutClass == EditorLayoutClass.Wide) {
                                        "editorWidePaneRow"
                                    } else {
                                        "editorExpandedPaneRow"
                                    },
                                ),
                        ) {
                            // Primary pane: preview + filmstrip under canvas (F1).
                            Column(
                                modifier = Modifier
                                    .weight(1f, fill = true)
                                    .fillMaxHeight()
                                    .testTag("editorPrimaryPreviewPane"),
                            ) {
                                EditorPreviewFrame(
                                    hasImage = hasPreviewContent,
                                    emptyText = emptyPreview,
                                    lowResolutionHint = lowResolutionHint,
                                    modifier = Modifier
                                        .weight(1f, fill = true)
                                        .fillMaxWidth()
                                        .testTag("sharedComposeWatermarkPreview"),
                                ) { previewModifier ->
                                    preview(previewModifier)
                                }
                                EditorMainFilmstrip(
                                    imageList = imageList,
                                    selected = selected,
                                    progressiveSlots = progressiveSlots,
                                    hasProgressiveSlots = hasProgressiveSlots,
                                    thumbnail = thumbnail,
                                    onImageSelected = onImageSelected,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            // Supporting form inspector — top tabs + scroll body (no phone Center chrome).
                            EditorInspectorPanel(
                                waterMark = waterMark,
                                templateIcon = icons.templateList,
                                onValueChange = onConfigChange,
                                onGoTemplateList = showTemplateSheet,
                                colorOption = colorOption,
                                iconOption = iconOption,
                                initialTabIndex = initialInspectorTab,
                                currentFontName = fontPanelState.currentDisplayName,
                                supportedStyles = fontPanelState.supportedStyles.styles,
                                onOpenFontPanel = showFontSheet,
                                modifier = Modifier
                                    .width(EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP.dp)
                                    .widthIn(max = EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP.dp)
                                    .fillMaxHeight()
                                    .padding(EDITOR_SUPPORTING_PANE_PADDING_DP.dp)
                                    .testTag("editorExpandedControlsPane"),
                            )
                        }
                    } else {
                        // Compact / Medium: phone vertical stack (M1).
                        // C1: this slot keeps its weighted height; the refit shrinks the tagged
                        // preview node inside it so `preview.bottom <= overlay.top`.
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val refit = previewRefitPx.value
                                        .coerceIn(0, constraints.maxHeight)
                                    val placeable = measurable.measure(
                                        constraints.copy(
                                            minHeight = (constraints.minHeight - refit).coerceAtLeast(0),
                                            maxHeight = (constraints.maxHeight - refit).coerceAtLeast(0),
                                        ),
                                    )
                                    layout(placeable.width, constraints.maxHeight) {
                                        placeable.placeRelative(0, 0)
                                    }
                                },
                        ) {
                            EditorPreviewFrame(
                                hasImage = hasPreviewContent,
                                emptyText = emptyPreview,
                                lowResolutionHint = lowResolutionHint,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("sharedComposeWatermarkPreview"),
                            ) { previewModifier ->
                                preview(previewModifier)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { bottomChromeContentPx = it.height },
                        ) {
                            EditorMainFilmstrip(
                                imageList = imageList,
                                selected = selected,
                                progressiveSlots = progressiveSlots,
                                hasProgressiveSlots = hasProgressiveSlots,
                                thumbnail = thumbnail,
                                onImageSelected = onImageSelected,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            EditorBottomControls(
                                waterMark = waterMark,
                                templateIcon = icons.templateList,
                                onValueChange = onConfigChange,
                                onGoTemplateList = showTemplateSheet,
                                colorOption = colorOption,
                                iconOption = iconOption,
                                optionItem = optionItem,
                                modifier = Modifier.fillMaxWidth(),
                                initialTabIndex = forcedBottomTab,
                                initialOptionIndex = forcedOptionIndex,
                                currentFontName = fontPanelState.currentDisplayName,
                                supportedStyles = fontPanelState.supportedStyles.styles,
                                onOpenFontPanel = showFontSheet,
                            )
                        }
                    }
                }
                if (!dualOrWide) {
                    AnimatedVisibility(
                        visible = overlayBody != null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .layout { measurable, constraints ->
                                // C1: fixed height. Root height comes from these constraints rather
                                // than state, so the first frame is already the right size.
                                val inner = if (constraints.hasBoundedHeight) {
                                    val h = editorBottomOverlayHeightPx(
                                        rootHeightPx = constraints.maxHeight,
                                        bottomChromePx = bottomChromePx,
                                        maxOverlayPx = overlayMaxPx,
                                    )
                                    constraints.copy(minHeight = h, maxHeight = h)
                                } else {
                                    constraints
                                }
                                val p = measurable.measure(inner)
                                layout(p.width, p.height) { p.placeRelative(0, 0) }
                            }
                            // The retained body stays composed for the exit transition. Once the
                            // host has cleared it, it is a ghost on its way out: eat pointer input
                            // in the Initial pass so nothing in it can still be tapped or dragged.
                            .then(
                                if (overlayBody == null) {
                                    Modifier.pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(PointerEventPass.Initial)
                                                    .changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                        enter = slideInVertically(
                            animationSpec = tween(overlaySlideMs, easing = FastOutSlowInEasing),
                            initialOffsetY = { it },
                        ) + fadeIn(animationSpec = tween(overlayFadeMs, easing = FastOutSlowInEasing)),
                        exit = slideOutVertically(
                            animationSpec = tween(overlaySlideMs, easing = FastOutSlowInEasing),
                            targetOffsetY = { it },
                        ) + fadeOut(animationSpec = tween(overlayFadeMs, easing = FastOutSlowInEasing)),
                        label = "editorBottomOverlay",
                    ) {
                        retainedOverlayBody.value?.invoke()
                    }
                }
            }
        }
    }
    }
    }
}

/** Filmstrip under primary preview — F1 single ownership. */
@Composable
private fun EditorMainFilmstrip(
    imageList: List<ImageInfoUi>,
    selected: ImageInfoUi?,
    progressiveSlots: EditorProgressiveSlotPresentation?,
    hasProgressiveSlots: Boolean,
    thumbnail: @Composable (image: ImageInfoUi, contentDescription: String, modifier: Modifier) -> Unit,
    onImageSelected: (ImageInfoUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Parent tag stays on this wrapper. The strip scaffold owns editorPhotoStrip /
    // progressiveImportFilmstrip — chaining both testTags on one Box overwrites one of them.
    if (progressiveSlots != null && hasProgressiveSlots) {
        Box(modifier.testTag("editorMainFilmstrip")) {
            EditorProgressivePhotoStrip(
                presentation = progressiveSlots,
                thumbnail = thumbnail,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else if (imageList.isNotEmpty()) {
        Box(modifier.testTag("editorMainFilmstrip")) {
            EditorPhotoStrip(
                images = imageList,
                selectedImage = selected,
                modifier = Modifier.fillMaxWidth(),
                onImageSelected = onImageSelected,
                thumbnail = thumbnail,
            )
        }
    }
}

