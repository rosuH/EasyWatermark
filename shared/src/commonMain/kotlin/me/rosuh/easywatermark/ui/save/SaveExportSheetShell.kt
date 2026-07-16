package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_output
import me.rosuh.easywatermark.shared.generated.resources.dialog_open_in_gallery
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_config_format
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_config_quality
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_list_title
import me.rosuh.easywatermark.shared.generated.resources.tips_images_selected
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import org.jetbrains.compose.resources.stringResource

/**
 * Shared CMP shell for the save/export modal sheet.
 * S-i18n-2: labels from [Res].
 *
 * @param exportListSubtitle argument for [Res.string.dialog_save_export_list_title] (e.g. result summary).
 * @param imageCount used for empty-preview plural string when [items] is empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SaveExportSheetShell(
    items: List<T>,
    selectedFormat: ImageFormat,
    quality: Int,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean = true,
    showOpenGallery: Boolean = true,
    exportListSubtitle: String = "",
    imageCount: Int = items.size,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExportClick: () -> Unit,
    onOpenGalleryClick: () -> Unit,
    itemKey: ((T) -> Any)? = null,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val outputTitle = stringResource(Res.string.about_title_output)
    val formatLabel = stringResource(Res.string.dialog_save_config_format)
    val qualityLabel = stringResource(Res.string.dialog_save_config_quality)
    val exportListTitle = stringResource(Res.string.dialog_save_export_list_title, exportListSubtitle)
    val emptyPreviewText = stringResource(Res.string.tips_images_selected, imageCount)
    val openGalleryLabel = stringResource(Res.string.dialog_open_in_gallery)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        shape = RectangleShape,
        // Match editor olive surface (not elevated Material surfaceVariant).
        containerColor = DesignEditorBg,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            SaveExportOptionsSection(
                title = outputTitle,
                formatLabel = formatLabel,
                qualityLabel = qualityLabel,
                selectedFormat = selectedFormat,
                quality = quality,
                enabled = primaryActionEnabled,
                onFormatClick = onFormatClick,
                onQualityChange = onQualityChange,
            )

            Text(
                text = exportListTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SaveExportPreviewBox(
                items = items,
                emptyText = emptyPreviewText,
                itemKey = itemKey,
                thumbnail = thumbnail,
            )

            Button(
                onClick = onExportClick,
                enabled = primaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(48.dp)
                    .testTag("sharedComposeExportPrimary"),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesignBrand,
                    contentColor = DesignEditorBg,
                    disabledContainerColor = DesignBrand.copy(alpha = 0.4f),
                    disabledContentColor = DesignEditorBg.copy(alpha = 0.6f),
                ),
            ) {
                Text(primaryActionLabel)
            }

            if (showOpenGallery) {
                TextButton(
                    onClick = onOpenGalleryClick,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 20.dp),
                    shape = RectangleShape,
                ) {
                    Text(text = openGalleryLabel)
                }
            }
        }
    }
}
