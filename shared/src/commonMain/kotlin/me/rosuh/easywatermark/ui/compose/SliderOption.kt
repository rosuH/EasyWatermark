package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Shared (commonMain) slider option for watermark numeric settings.
 *
 * S4d-238 resource strategy: the caller supplies a [valueRange] and a plain value callback so
 * Android resource/domain state stays in [app/src/main/java/me/rosuh/easywatermark/ui/EditorScreen.kt].
 * This composable has no `R`, `stringResource`, `Preview`, `FuncTitleModel`, or `FuncType` dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderOption(
    currentValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
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
                onValueChange(it.toInt().toFloat())
            },
            steps = (valueRange.endInclusive - valueRange.start).toInt(),
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
            valueRange = valueRange
        )
        Text(text = currentValue.toInt().toString())
    }
}
