package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkConfigChange
import me.rosuh.easywatermark.data.model.entity.Template
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_about
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_navigate_up
import me.rosuh.easywatermark.shared.generated.resources.cd_save
import me.rosuh.easywatermark.shared.generated.resources.tips_no_image_selected
import org.jetbrains.compose.resources.stringResource

/**
 * Shared product editor screen. S-i18n-2: chrome labels from [Res]; hosts inject painters + slots.
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
    imageList: List<ImageInfo>,
    waterMark: WaterMark,
    selectedImage: ImageInfo?,
    templates: List<Template>,
    icons: EditorUiIcons,
    preview: @Composable (Modifier) -> Unit,
    thumbnail: @Composable (image: ImageInfo, contentDescription: String, modifier: Modifier) -> Unit,
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
    onImageSelected: (ImageInfo) -> Unit,
    onConfigChange: (WatermarkConfigChange) -> Unit,
    onUseTemplate: (Template) -> Unit,
    onAddTemplate: (String) -> Unit,
    onUpdateTemplate: (Template) -> Unit,
    onDeleteTemplate: (Template) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = selectedImage ?: imageList.firstOrNull()
    val emptyPreview = stringResource(Res.string.tips_no_image_selected)
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
                    .safeDrawingPadding(),
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

                EditorPreviewFrame(
                    hasImage = selected != null,
                    emptyText = emptyPreview,
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .fillMaxWidth()
                        .testTag("sharedComposeWatermarkPreview"),
                ) { previewModifier ->
                    preview(previewModifier)
                }

                if (imageList.isNotEmpty()) {
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
