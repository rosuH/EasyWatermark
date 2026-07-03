package me.rosuh.easywatermark.ui.save

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.ImageFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveExportSheet(
    imageCount: Int,
    imageUris: List<Uri> = emptyList(),
    selectedFormatLabel: ImageFormat,
    quality: Int,
    isSaving: Boolean,
    finishedCount: Int,
    totalCount: Int,
    resultSummaryText: String,
    primaryActionLabel: String,
    onDismiss: () -> Unit,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExportClick: () -> Unit,
    onShareClick: () -> Unit,
    onOpenGalleryClick: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        ),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            SaveExportOptionsSection(
                title = stringResource(R.string.about_title_output),
                formatLabel = stringResource(R.string.dialog_save_config_format),
                qualityLabel = stringResource(R.string.dialog_save_config_quality),
                selectedFormat = selectedFormatLabel,
                quality = quality,
                onFormatClick = onFormatClick,
                onQualityChange = onQualityChange,
            )

            Text(
                text = stringResource(
                    R.string.dialog_save_export_list_title,
                    resultSummaryText,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 30.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            SaveExportPreviewBox(
                items = imageUris,
                emptyText = "$imageCount image(s) selected",
            ) { uri, thumbnailModifier ->
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = thumbnailModifier,
                )
            }

            Button(
                onClick = onExportClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 43.dp),
                shape = RectangleShape
            ) {
                Text(primaryActionLabel)
            }

            TextButton(
                onClick = onOpenGalleryClick,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 20.dp),
                shape = RectangleShape
            ) {
                Text(
                    text = stringResource(R.string.dialog_open_in_gallery)
                )
            }
        }
    }
}






