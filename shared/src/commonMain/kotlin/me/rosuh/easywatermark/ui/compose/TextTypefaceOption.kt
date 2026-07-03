package me.rosuh.easywatermark.ui.compose

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rosuh.easywatermark.data.model.TextTypeface

/**
 * Shared (commonMain) segmented control for watermark text typeface.
 *
 * S4d-238 resource strategy: labels are passed as [TextTypefaceLabels] so the Android caller
 * resolves `stringResource(R.string.*)` at the edge while Desktop/iOS can pass hard-coded
 * or localized strings. This composable has no `R`/`stringResource`/`FuncTitleModel` dependencies.
 */
data class TextTypefaceLabels(
    val normal: String,
    val bold: String,
    val italic: String,
    val boldItalic: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextTypeface(
    labels: TextTypefaceLabels,
    typeface: TextTypeface,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (TextTypeface) -> Unit,
) {
    val options = listOf(
        labels.normal to TextTypeface.Normal,
        labels.bold to TextTypeface.Bold,
        labels.italic to TextTypeface.Italic,
        labels.boldItalic to TextTypeface.BoldItalic,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, textTypefacePair ->
            SegmentedButton(
                selected = typeface == textTypefacePair.second,
                enabled = enabled,
                onClick = {
                    onValueChange(textTypefacePair.second)
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(
                    text = textTypefacePair.first,
                    fontStyle = if (textTypefacePair.second == TextTypeface.Normal || textTypefacePair.second == TextTypeface.Bold) {
                        FontStyle.Normal
                    } else {
                        FontStyle.Italic
                    },
                    style = if (textTypefacePair.second == TextTypeface.Bold || textTypefacePair.second == TextTypeface.BoldItalic) {
                        TextStyle(fontWeight = FontWeight.Bold)
                    } else {
                        TextStyle(fontWeight = FontWeight.Normal)
                    }
                )
            }
        }
    }
}
