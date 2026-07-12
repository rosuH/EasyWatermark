package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/**
 * Shared CMP shell for the save/export preview list.
 *
 * Android still supplies the thumbnail renderer because exported image URIs are a platform edge.
 */
@Composable
fun <T> SaveExportPreviewBox(
    items: List<T>,
    emptyText: String,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            // Slightly shorter than the old 145.dp so format/quality + CTA fit under a
            // wrap-height sheet with dimmed editor peek (production export chrome).
            .height(110.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RectangleShape,
            )
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(items) { item ->
                    thumbnail(
                        item,
                        Modifier.size(96.dp),
                    )
                }
            }
        }
    }
}
