package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.ImageInfoUi
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.data.model.toUiProjection
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
 * ADR-0026 layout:
 * - Compact / Medium: vertical stack (preview → filmstrip → bottom controls)
 * - Expanded (≥840): supporting-pane A — main (preview + filmstrip) | fixed inspector rail
 * - Wide (≥1440): three-zone C — session library | main (preview + filmstrip) | inspector
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
     * Display list for filmstrip / C session library (immutable projection — no export jobState/result vars).
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
                    // A / C: horizontal chrome. Filmstrip stays in main column (F1).
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
                        if (layoutClass == EditorLayoutClass.Wide) {
                            // C-L4: session image library only (templates stay sheet).
                            EditorSessionImageLibrary(
                                images = imageList,
                                selectedImage = selected,
                                progressive = progressiveSlots,
                                thumbnail = thumbnail,
                                onImageSelected = onImageSelected,
                                modifier = Modifier
                                    .width(EDITOR_WIDE_SESSION_LIBRARY_MAX_DP.dp)
                                    .fillMaxHeight()
                                    .testTag("editorWideSessionLibrary"),
                            )
                        }

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

/**
 * Three-zone C left rail: session images only (C-L4). Vertical list reuses [thumbnail];
 * selection routes through the same [onImageSelected] as the filmstrip.
 */
@Composable
private fun EditorSessionImageLibrary(
    images: List<ImageInfoUi>,
    selectedImage: ImageInfoUi?,
    progressive: EditorProgressiveSlotPresentation?,
    thumbnail: @Composable (image: ImageInfoUi, contentDescription: String, modifier: Modifier) -> Unit,
    onImageSelected: (ImageInfoUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(2.dp)
    val selectedUri = selectedImage?.uri?.value
    val pad = EDITOR_SUPPORTING_PANE_PADDING_DP.dp
    Column(
        modifier = modifier
            .padding(pad)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // Prefer ready progressive slots when the host is mid-import; else session imageList.
        val readyFromProgressive = progressive?.state?.slots
            ?.filterIsInstance<EditorMediaSlot.Ready>()
            ?.map { it.image.toUiProjection() }
            .orEmpty()
        val libraryImages = readyFromProgressive.ifEmpty { images }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("editorSessionLibraryList"),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(
                items = libraryImages,
                key = { it.uri.value },
            ) { image ->
                val selected = image.uri.value == selectedUri
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onImageSelected(image) }
                        .padding(horizontal = 4.dp)
                        .testTag("editorSessionLibraryItem"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(shape)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = 1.5.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = shape,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        thumbnail(image, "session image", Modifier.fillMaxSize().clip(shape))
                    }
                    Text(
                        text = image.uri.value.substringAfterLast('/').ifEmpty { image.uri.value },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
