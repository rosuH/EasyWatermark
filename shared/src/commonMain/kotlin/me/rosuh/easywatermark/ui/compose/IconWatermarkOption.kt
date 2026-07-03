package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Shared CMP shell for the image-watermark picker option.
 *
 * Android still supplies permission handling, picker launch, URI conversion, and thumbnail loading.
 */
@Composable
fun IconWatermarkOption(
    hasIcon: Boolean,
    pickLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        if (hasIcon) {
            preview()
        }
        Button(
            enabled = enabled,
            onClick = onPick,
        ) {
            Text(text = pickLabel)
        }
    }
}
