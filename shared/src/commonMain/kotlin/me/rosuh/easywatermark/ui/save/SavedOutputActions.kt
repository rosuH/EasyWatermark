package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SavedOutputActionsLabels(
    val primary: String,
    val secondary: String,
)

/**
 * Shared two-action row for a completed output.
 *
 * Callers that share one availability gate keep [hasOutput] + [enabled] (both buttons default
 * To that combined state). Platforms that stage artifacts independently pass * [primaryEnabled] / [secondaryEnabled] so a primary-only staging failure does not disable
 * the secondary action (e.g. iOS Share temp file vs Save from in-memory PNG).
 */
@Composable
fun SavedOutputActions(
    labels: SavedOutputActionsLabels,
    hasOutput: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primaryEnabled: Boolean = enabled && hasOutput,
    secondaryEnabled: Boolean = enabled && hasOutput,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            enabled = primaryEnabled,
            onClick = onPrimaryAction,
        ) {
            Text(labels.primary)
        }
        Button(
            enabled = secondaryEnabled,
            onClick = onSecondaryAction,
        ) {
            Text(labels.secondary)
        }
    }
}
