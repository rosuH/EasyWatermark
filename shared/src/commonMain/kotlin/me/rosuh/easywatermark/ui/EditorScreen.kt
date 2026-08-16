package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_navigate_up
import me.rosuh.easywatermark.shared.generated.resources.cd_save
import me.rosuh.easywatermark.shared.generated.resources.ios_import_preparing
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
) {
    val progressiveSlots = LocalEditorProgressiveSlotPresentation.current
    val selected = selectedImage ?: imageList.firstOrNull()
    val hasProgressiveSlots = progressiveSlots?.state?.slots?.isNotEmpty() == true
    val hasPreviewContent = selected != null || hasProgressiveSlots
    val emptyPreview = if (hasProgressiveSlots) {
        stringResource(Res.string.ios_import_preparing)
    } else {
        stringResource(Res.string.tips_no_image_selected)
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
    ) { showTemplateSheet ->
        Surface(
            modifier = modifier
                .fillMaxSize()
                .testTag("sharedComposeEditorScreen"),
            color = MaterialTheme.colorScheme.background,
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
                    EditorPreviewFrame(
                        hasImage = hasPreviewContent,
                        emptyText = emptyPreview,
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

                    EditorBottomControls(
                        waterMark = waterMark,
                        templateIcon = icons.templateList,
                        onValueChange = onConfigChange,
                        onGoTemplateList = showTemplateSheet,
                        colorOption = colorOption,
                        iconOption = iconOption,
                        optionItem = optionItem,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
    if (progressiveSlots != null && hasProgressiveSlots) {
        EditorProgressivePhotoStrip(
            presentation = progressiveSlots,
            thumbnail = thumbnail,
            modifier = modifier.testTag("editorMainFilmstrip"),
        )
    } else if (imageList.isNotEmpty()) {
        EditorPhotoStrip(
            images = imageList,
            selectedImage = selected,
            modifier = modifier.testTag("editorMainFilmstrip"),
            onImageSelected = onImageSelected,
            thumbnail = thumbnail,
        )
    }
}

