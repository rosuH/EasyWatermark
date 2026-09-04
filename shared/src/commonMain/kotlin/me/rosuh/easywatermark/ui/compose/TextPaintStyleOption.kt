package me.rosuh.easywatermark.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.text_paint_fill
import me.rosuh.easywatermark.shared.generated.resources.text_paint_stroke
import org.jetbrains.compose.resources.stringResource

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
    fillMaxWidth: Boolean = true,
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
        fillMaxWidth = fillMaxWidth,
    )
}

@Composable
fun rememberTextPaintStyleLabels(): TextPaintStyleLabels = TextPaintStyleLabels(
    fill = stringResource(Res.string.text_paint_fill),
    stroke = stringResource(Res.string.text_paint_stroke),
)
