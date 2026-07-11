package me.rosuh.easywatermark.ui.compose

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.rosuh.easywatermark.data.model.TextPaintStyle

/**
 * Shared (commonMain) segmented control for watermark text paint style.
 *
 * Labels are caller-supplied so Android can keep resource lookup at the app edge while Desktop/iOS can
 * pass local strings. This component owns only the Fill/Stroke segmented shell.
 */
data class TextPaintStyleLabels(
    val fill: String,
    val stroke: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPaintStyleOption(
    labels: TextPaintStyleLabels,
    style: TextPaintStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (TextPaintStyle) -> Unit,
) {
    val options = listOf(
        labels.fill to TextPaintStyle.Fill,
        labels.stroke to TextPaintStyle.Stroke,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, pair ->
            SegmentedButton(
                selected = style == pair.second,
                enabled = enabled,
                onClick = { onValueChange(pair.second) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.semantics { contentDescription = pair.first },
            ) {
                Text(text = pair.first)
            }
        }
    }
}
