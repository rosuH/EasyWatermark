package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SavedOutputActionsLabels(
    val showInFolder: String,
    val copyPath: String,
)

@Composable
fun SavedOutputActions(
    labels: SavedOutputActionsLabels,
    hasSavedOutput: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onShowInFolder: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val actionEnabled = enabled && hasSavedOutput
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            enabled = actionEnabled,
            onClick = onShowInFolder,
        ) {
            Text(labels.showInFolder)
        }
        Button(
            enabled = actionEnabled,
            onClick = onCopyPath,
        ) {
            Text(labels.copyPath)
        }
    }
}
