package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/**
 * Shared CMP shell for one editor bottom-option item.
 *
 * Platform callers still supply resource-backed painters and localized labels.
 */
@Composable
fun EditorOptionItem(
    icon: Painter,
    contentDescription: String,
    label: String,
) {
    Icon(
        painter = icon,
        contentDescription = contentDescription,
        modifier = Modifier.height(24.dp),
    )
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}
