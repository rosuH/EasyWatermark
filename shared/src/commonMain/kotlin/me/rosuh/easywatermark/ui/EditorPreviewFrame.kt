package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared CMP frame for the editor preview area.
 *
 * Android still supplies the actual preview renderer because production preview/export reuse the
 * native Android watermark renderer.
 */
@Composable
fun EditorPreviewFrame(
    hasImage: Boolean,
    emptyText: String,
    modifier: Modifier = Modifier,
    preview: @Composable (modifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        if (hasImage) {
            preview(Modifier.fillMaxSize())
        } else {
            Text(text = emptyText, Modifier.align(Alignment.Center))
        }
    }
}
