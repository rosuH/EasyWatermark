package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class WatermarkModeActionsLabels(
    val pickIcon: String,
    val useTextWatermark: String,
    val preview: String,
)

@Composable
fun WatermarkModeActions(
    labels: WatermarkModeActionsLabels,
    hasIcon: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onPickIcon: () -> Unit,
    onUseTextWatermark: () -> Unit,
    onPreview: () -> Unit,
    iconPreview: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconWatermarkOption(
            hasIcon = hasIcon,
            pickLabel = labels.pickIcon,
            enabled = !busy,
            onPick = onPickIcon,
            preview = iconPreview,
        )
        Button(
            enabled = !busy,
            onClick = onUseTextWatermark,
        ) {
            Text(labels.useTextWatermark)
        }
        Button(
            enabled = !busy,
            onClick = onPreview,
        ) {
            Text(labels.preview)
        }
    }
}
