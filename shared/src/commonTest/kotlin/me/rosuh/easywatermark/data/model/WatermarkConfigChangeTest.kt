package me.rosuh.easywatermark.data.model

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F2: typed [WatermarkConfigChange] constructors at control source.
 * Gap rounding matches the legacy `(Float).roundToInt()` that lived in `from(Horizon/Vertical)`.
 */
class WatermarkConfigChangeTest {

    @Test
    fun typed_commands_construct_for_each_control_family() {
        assertEquals(WatermarkConfigChange.Text("hi"), WatermarkConfigChange.Text("hi"))
        assertEquals(
            WatermarkConfigChange.Icon(MediaRef.parse("u")),
            WatermarkConfigChange.Icon(MediaRef.parse("u")),
        )
        assertEquals(WatermarkConfigChange.Color(-19968), WatermarkConfigChange.Color(-19968))
        assertEquals(WatermarkConfigChange.AlphaPercent(50f), WatermarkConfigChange.AlphaPercent(50f))
        assertEquals(WatermarkConfigChange.Degree(315f), WatermarkConfigChange.Degree(315f))
        assertEquals(WatermarkConfigChange.TextSize(14f), WatermarkConfigChange.TextSize(14f))
        assertEquals(
            WatermarkConfigChange.Typeface(TextTypeface.Bold),
            WatermarkConfigChange.Typeface(TextTypeface.Bold),
        )
        assertEquals(
            WatermarkConfigChange.TileMode(WatermarkTileMode.REPEAT),
            WatermarkConfigChange.TileMode(WatermarkTileMode.REPEAT),
        )
    }

    @Test
    fun horizontal_and_vertical_gaps_round_to_int_at_emission() {
        // Same rule as controls: (sliderFloat).roundToInt() before HorizontalGap/VerticalGap.
        assertEquals(13, 12.7f.roundToInt())
        assertEquals(12, 12.4f.roundToInt())
        assertEquals(13, 12.5f.roundToInt()) // half-up
        assertEquals(0, 0.2f.roundToInt())
        assertEquals(
            WatermarkConfigChange.HorizontalGap(12.7f.roundToInt()),
            WatermarkConfigChange.HorizontalGap(13),
        )
        assertEquals(
            WatermarkConfigChange.VerticalGap(12.5f.roundToInt()),
            WatermarkConfigChange.VerticalGap(13),
        )
    }
}
