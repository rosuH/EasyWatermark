package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.ImageFormat

data class SaveExportSheetStrings(
    val outputTitle: String,
    val formatLabel: String,
    val qualityLabel: String,
    val exportListTitle: String,
    val emptyPreviewText: String,
    val openGalleryLabel: String,
)

/**
 * Shared CMP shell for the save/export modal sheet.
 *
 * Platform callers still supply thumbnails and side-effecting actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SaveExportSheetShell(
    items: List<T>,
    selectedFormat: ImageFormat,
    quality: Int,
    strings: SaveExportSheetStrings,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean = true,
    showOpenGallery: Boolean = true,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExportClick: () -> Unit,
    onOpenGalleryClick: () -> Unit,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    // Phase B: wrap-height content (no fillMaxSize) so the dimmed editor peeks above.
    // Expand fully to content height so the primary CTA is not clipped by a half-expanded settle.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
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
                title = strings.outputTitle,
                formatLabel = strings.formatLabel,
                qualityLabel = strings.qualityLabel,
                selectedFormat = selectedFormat,
                quality = quality,
                enabled = primaryActionEnabled,
                onFormatClick = onFormatClick,
                onQualityChange = onQualityChange,
            )

            Text(
                text = strings.exportListTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SaveExportPreviewBox(
                items = items,
                emptyText = strings.emptyPreviewText,
                thumbnail = thumbnail,
            )

            Button(
                onClick = onExportClick,
                enabled = primaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                shape = RectangleShape,
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
                    Text(text = strings.openGalleryLabel)
                }
            }
        }
    }
}
