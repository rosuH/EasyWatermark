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
import me.rosuh.easywatermark.data.model.WatermarkTileMode

/**
 * Shared (commonMain) segmented control for watermark tile mode.
 *
 * S4d-238 resource strategy: labels are passed as [TileModeLabels] so the Android caller
 * resolves `stringResource(R.string.*)` at the edge while Desktop/iOS can pass hard-coded
 * or localized strings. This composable has no `R`/`stringResource`/`FuncTitleModel` dependencies.
 */
data class TileModeLabels(
    val repeat: String,
    val decal: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileMode(
    labels: TileModeLabels,
    mode: WatermarkTileMode,
    modifier: Modifier = Modifier,
    onValueChange: (WatermarkTileMode) -> Unit,
) {
    // Product modes only: "repeat" and the single-decal "decal" mode (backed by CLAMP at the
    // Android edge). Neutral WatermarkTileMode flows UI -> MainViewModel -> repository (S1).
    val options = listOf(
        labels.repeat to WatermarkTileMode.REPEAT,
        labels.decal to WatermarkTileMode.CLAMP,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, pair ->
            SegmentedButton(
                selected = mode == pair.second,
                onClick = {
                    onValueChange(pair.second)
                },
                modifier = Modifier.semantics { contentDescription = "Localized Description" },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(text = pair.first)
            }
        }
    }
}
