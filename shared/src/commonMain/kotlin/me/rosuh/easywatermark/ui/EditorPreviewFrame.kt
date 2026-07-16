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
 * Hosts supply the actual renderer. [preview] receives a modifier that fills this frame so the
 * image can scale with ContentScale.Fit responsively (not a fixed max height).
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
        contentAlignment = Alignment.Center,
    ) {
        if (hasImage) {
            // Fill *this* Box only — do not re-apply the outer frame [modifier].
            preview(Modifier.fillMaxSize())
        } else {
            Text(text = emptyText, Modifier.align(Alignment.Center))
        }
    }
}
