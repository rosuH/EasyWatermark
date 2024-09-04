package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel


@Preview
@Composable
private fun SliderOptionPreview() {
    val item = FuncTitleModel(
        FuncTitleModel.FuncType.Alpha,
        R.string.style_alpha,
        R.drawable.ic_func_opacity
    )
    SliderOption(item, 0f) { _, _ -> }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderOption(
    item: FuncTitleModel,
    currentValue: Float,
    modifier: Modifier = Modifier,
    onValueChange: (item: FuncTitleModel, value: Float) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
        val colors = SliderDefaults.colors()
        Slider(
            value = currentValue,
            onValueChange = {
                onValueChange(item, it.toInt().toFloat())
            },
            steps = (item.valueRange.endInclusive - item.valueRange.start).toInt(),
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    enabled = true
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    colors = SliderDefaults.colors(
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                    enabled = true,
                    sliderState = sliderState
                )
            },
            valueRange = item.valueRange
        )
        Text(text = currentValue.toInt().toString())
    }
}