package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared CMP shell for a save/preview status plus an optional rendered preview bitmap.
 *
 * Platform callers still own rendering, decoding, and persistence side effects.
 */
@Composable
fun SavePreviewStatus(
    status: String,
    preview: ImageBitmap?,
    previewContentDescription: String,
    modifier: Modifier = Modifier,
    maxPreviewHeight: Dp = 360.dp,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status, style = MaterialTheme.typography.bodyMedium)
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = previewContentDescription,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxPreviewHeight),
            )
        }
    }
}
