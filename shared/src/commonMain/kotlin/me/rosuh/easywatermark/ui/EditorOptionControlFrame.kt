package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EditorOptionControlFrame(
    modifier: Modifier = Modifier,
    content: @Composable (innerModifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
    ) {
        content(
            Modifier
                .fillMaxWidth()
                // Tighter vertical padding so Content text field / sliders remain fully visible
                // under the previous hard 56.dp clip (Phase B ticket 08).
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
