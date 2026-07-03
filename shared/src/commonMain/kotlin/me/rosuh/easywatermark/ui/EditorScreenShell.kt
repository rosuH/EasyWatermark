package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Shared CMP shell for the editor screen's vertical layout.
 *
 * Platform callers still provide the top bar, renderer, thumbnail strip, and bottom editor controls.
 */
@Composable
fun EditorScreenShell(
    showPhotoStrip: Boolean,
    modifier: Modifier = Modifier,
    topBar: @Composable (modifier: Modifier) -> Unit,
    preview: @Composable (modifier: Modifier) -> Unit,
    photoStrip: @Composable (modifier: Modifier) -> Unit,
    bottomControls: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        topBar(Modifier.fillMaxWidth())
        preview(Modifier.weight(1f, true))
        if (showPhotoStrip) {
            photoStrip(Modifier.fillMaxWidth())
        }
        bottomControls()
    }
}
