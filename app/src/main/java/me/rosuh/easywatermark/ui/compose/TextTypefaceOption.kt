package me.rosuh.easywatermark.ui.compose

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark

@Preview
@Composable
private fun TextStylePreview() {
    TextTypeface(
        item = FuncTitleModel(
            FuncTitleModel.FuncType.TextTypeFace,
            R.string.title_text_style,
            R.drawable.ic_func_typeface
        ),
        waterMark = WaterMark.default,
        onValueChange = { _, _ -> }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextTypeface(
    item: FuncTitleModel,
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    onValueChange: (item: FuncTitleModel, value: Any) -> Unit,
) {
    val options = listOf(
        stringResource(id = R.string.text_typeface_normal) to TextTypeface.Normal,
        stringResource(id = R.string.text_typeface_bold) to TextTypeface.Bold,
        stringResource(id = R.string.text_typeface_italic) to TextTypeface.Italic,
        stringResource(id = R.string.text_typeface_bold_italic) to TextTypeface.BoldItalic,
    )
    var selectedIndex by remember {
        mutableIntStateOf(
            when (waterMark.textTypeface) {
                TextTypeface.Normal -> 0
                TextTypeface.Bold -> 1
                TextTypeface.Italic -> 2
                TextTypeface.BoldItalic -> 3
            }
        )
    }
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
    ) {
        options.forEachIndexed { index, textTypefacePair ->
            SegmentedButton(
                selected = selectedIndex == index,
                onClick = {
                    selectedIndex = index
                    onValueChange(item, textTypefacePair.second)
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