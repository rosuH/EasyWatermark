package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.theme.EwmTheme

/**
 * Shared CMP frame for the editor preview area.
 *
 * Hosts supply the actual renderer. [preview] receives a modifier that fills this frame so the
 * Image can scale with ContentScale.Fit responsively (not a fixed max height). */
@Composable
fun EditorPreviewFrame(
    hasImage: Boolean,
    emptyText: String,
    modifier: Modifier = Modifier,
    lowResolutionHint: String? = null,
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
            if (lowResolutionHint != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .testTag("editorLowResolutionHint"),
                    shape = RoundedCornerShape(EwmTheme.shapes.chipRadiusDp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = lowResolutionHint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        } else {
            Text(text = emptyText, Modifier.align(Alignment.Center))
        }
    }
}
