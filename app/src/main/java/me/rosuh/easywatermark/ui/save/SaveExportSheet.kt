package me.rosuh.easywatermark.ui.save

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.ImageFormat

@Composable
fun SaveExportSheet(
    imageCount: Int,
    imageUris: List<Uri> = emptyList(),
    selectedFormatLabel: ImageFormat,
    quality: Int,
    resultSummaryText: String,
    primaryActionLabel: String,
    onDismiss: () -> Unit,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExportClick: () -> Unit,
    onOpenGalleryClick: () -> Unit,
) {
    SaveExportSheetShell(
        items = imageUris,
        selectedFormat = selectedFormatLabel,
        quality = quality,
        strings = SaveExportSheetStrings(
            outputTitle = stringResource(R.string.about_title_output),
            formatLabel = stringResource(R.string.dialog_save_config_format),
            qualityLabel = stringResource(R.string.dialog_save_config_quality),
            exportListTitle = stringResource(
                R.string.dialog_save_export_list_title,
                resultSummaryText,
            ),
            emptyPreviewText = "$imageCount image(s) selected",
            openGalleryLabel = stringResource(R.string.dialog_open_in_gallery),
        ),
        primaryActionLabel = primaryActionLabel,
        onDismiss = onDismiss,
        onFormatClick = onFormatClick,
        onQualityChange = onQualityChange,
        onExportClick = onExportClick,
        onOpenGalleryClick = onOpenGalleryClick,
    ) { uri, thumbnailModifier ->
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = thumbnailModifier,
        )
    }
}
