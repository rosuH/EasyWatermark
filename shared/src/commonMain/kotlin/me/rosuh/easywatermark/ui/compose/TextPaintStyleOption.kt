package me.rosuh.easywatermark.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rosuh.easywatermark.data.model.TextPaintStyle

/**
 * Shared paint-style choice chips (Fill / Stroke). Design-aligned — not Material SegmentedButton.
 */
data class TextPaintStyleLabels(
    val fill: String,
    val stroke: String,
)

@Composable
fun TextPaintStyleOption(
    labels: TextPaintStyleLabels,
    style: TextPaintStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (TextPaintStyle) -> Unit,
) {
    DesignChoiceChips(
        options = listOf(
            DesignChoiceOption(label = labels.fill, value = TextPaintStyle.Fill),
            DesignChoiceOption(label = labels.stroke, value = TextPaintStyle.Stroke),
        ),
        selected = style,
        onSelected = onValueChange,
        modifier = modifier,
        enabled = enabled,
    )
}
