package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
 * I1: optional [layoutClass] — Compact/Medium keep vertical stack; Expanded uses preview + controls pane.
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
     * Prefer [me.rosuh.easywatermark.data.model.toUiProjection] at the host boundary.
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
     * I1 layout class from host window size. Default [EditorLayoutClass.Compact] keeps
     * phone binary-compatible call sites.
     */
    layoutClass: EditorLayoutClass = EditorLayoutClass.Compact,
    /** Optional: host learns when template sheet opens/closes (defer template Flow collect). */
    onTemplateSheetVisibilityChange: (Boolean) -> Unit = {},
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

    EditorTemplateSheetHost(
        templates = templates,
        editIcon = icons.templateEdit,
        deleteIcon = icons.templateDelete,
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
                    .testTag(
                        when (layoutClass) {
                            EditorLayoutClass.Compact -> "editorLayoutCompact"
                            EditorLayoutClass.Medium -> "editorLayoutMedium"
                            EditorLayoutClass.Expanded -> "editorLayoutExpanded"
                        },
                    ),
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

                if (layoutClass == EditorLayoutClass.Expanded) {
                    // I1 expanded: preview (weight) + supporting controls column.
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth()
                            .testTag("editorExpandedPaneRow"),
                    ) {
                        EditorPreviewFrame(
                            hasImage = hasPreviewContent,
                            emptyText = emptyPreview,
                            modifier = Modifier
                                .weight(1f, fill = true)
                                .fillMaxHeight()
                                .testTag("sharedComposeWatermarkPreview"),
                        ) { previewModifier ->
                            preview(previewModifier)
                        }

                        Column(
                            modifier = Modifier
                                .widthIn(max = EDITOR_EXPANDED_CONTROLS_PANE_MAX_DP.dp)
                                .fillMaxHeight()
                                .fillMaxWidth(0.38f)
                                .testTag("editorExpandedControlsPane"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (progressiveSlots != null && hasProgressiveSlots) {
                                EditorProgressivePhotoStrip(
                                    presentation = progressiveSlots,
                                    thumbnail = thumbnail,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (imageList.isNotEmpty()) {
                                EditorPhotoStrip(
                                    images = imageList,
                                    selectedImage = selected,
                                    modifier = Modifier.fillMaxWidth(),
                                    onImageSelected = onImageSelected,
                                    thumbnail = thumbnail,
                                )
                            }
                            Box(modifier = Modifier.weight(1f, fill = true).fillMaxWidth()) {
                                EditorBottomControls(
                                    waterMark = waterMark,
                                    templateIcon = icons.templateList,
                                    onValueChange = onConfigChange,
                                    onGoTemplateList = showTemplateSheet,
                                    colorOption = colorOption,
                                    iconOption = iconOption,
                                    optionItem = optionItem,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                } else {
                    // Compact / Medium: existing phone vertical stack.
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

                    if (progressiveSlots != null && hasProgressiveSlots) {
                        EditorProgressivePhotoStrip(
                            presentation = progressiveSlots,
                            thumbnail = thumbnail,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (imageList.isNotEmpty()) {
                        EditorPhotoStrip(
                            images = imageList,
                            selectedImage = selected,
                            modifier = Modifier.fillMaxWidth(),
                            onImageSelected = onImageSelected,
                            thumbnail = thumbnail,
                        )
                    }

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
