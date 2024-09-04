package me.rosuh.easywatermark.ui.compose

import android.graphics.Shader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.util.packInts
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.WaterMark

@Preview
@Composable
private fun TileModePreview() {
    TileMode(
        item = FuncTitleModel(
            FuncTitleModel.FuncType.TileMode,
            R.string.title_tile_mode,
            R.drawable.ic_tile_mode
        ),
        waterMark = WaterMark.default,
        onValueChange = { _, _ -> }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileMode(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onValueChange: (item: FuncTitleModel, value: Any) -> Unit,
) {
    val options = listOf(
        stringResource(id = R.string.tile_mode_title_repeat) to Shader.TileMode.REPEAT,
        stringResource(id = R.string.tile_mode_title_decal) to Shader.TileMode.CLAMP,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, pair ->
            SegmentedButton(
                selected = waterMark.tileMode == pair.second,
                onClick = {
                    onValueChange(item, pair.second)
                },
                modifier = Modifier.semantics { contentDescription = "Localized Description" },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(text = pair.first)
            }
        }
    }
}
