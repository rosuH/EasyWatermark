package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/**
 * Shared CMP gallery-dialog top bar.
 *
 * Android still decides what close/search do; this shell only owns the row layout.
 */
@Composable
fun GalleryDialogTopBarShell(
    title: String,
    closeIcon: Painter,
    searchIcon: Painter,
    closeContentDescription: String,
    searchContentDescription: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                painter = closeIcon,
                contentDescription = closeContentDescription,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        IconButton(onClick = onSearch) {
            Icon(
                painter = searchIcon,
                contentDescription = searchContentDescription,
            )
        }
    }
}
