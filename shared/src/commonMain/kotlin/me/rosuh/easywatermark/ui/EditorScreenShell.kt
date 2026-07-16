package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * @deprecated DEBUG witness layout only. Production uses [EditorScreen].
 */
@Deprecated("Use EditorScreen for production product UI")
@Composable
fun EditorScreenShell(
    showPhotoStrip: Boolean,
    modifier: Modifier = Modifier,
    topBar: @Composable (modifier: Modifier) -> Unit,
    preview: @Composable (modifier: Modifier) -> Unit,
    photoStrip: @Composable (modifier: Modifier) -> Unit,
    bottomControls: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxSize(),
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
}
