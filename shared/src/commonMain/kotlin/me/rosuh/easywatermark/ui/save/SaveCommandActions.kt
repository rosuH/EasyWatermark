package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SaveCommandActionsLabels(
    val renderAndSave: String,
    val working: String,
    val saveAs: String,
    val openImage: String,
)

@Composable
fun SaveCommandActions(
    labels: SaveCommandActionsLabels,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onRenderAndSave: () -> Unit,
    onSaveAs: () -> Unit,
    onOpenImage: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            enabled = !busy,
            onClick = onRenderAndSave,
        ) {
            Text(if (busy) labels.working else labels.renderAndSave)
        }
        Button(
            enabled = !busy,
            onClick = onSaveAs,
        ) {
            Text(labels.saveAs)
        }
        Button(
            enabled = !busy,
            onClick = onOpenImage,
        ) {
            Text(labels.openImage)
        }
    }
}
