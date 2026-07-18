package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins `WatermarkConfigChange.from`: typed-command construction + the gap rounding and fail-fast
 * Cast behavior that were previously inline in `MainViewModel.onWaterMarkChanged`. */
class WatermarkConfigChangeTest {

    @Test
    fun typed_commands_construct_from_each_FuncType() {
        assertEquals(WatermarkConfigChange.Text("hi"), WatermarkConfigChange.from(FuncType.Text, "hi"))
        assertEquals(WatermarkConfigChange.Icon(MediaRef.parse("u")), WatermarkConfigChange.from(FuncType.Icon, MediaRef.parse("u")))
        assertEquals(WatermarkConfigChange.Color(-19968), WatermarkConfigChange.from(FuncType.Color, -19968))
        assertEquals(WatermarkConfigChange.AlphaPercent(50f), WatermarkConfigChange.from(FuncType.Alpha, 50f))
        assertEquals(WatermarkConfigChange.Degree(315f), WatermarkConfigChange.from(FuncType.Degree, 315f))
        assertEquals(WatermarkConfigChange.TextSize(14f), WatermarkConfigChange.from(FuncType.TextSize, 14f))
        assertEquals(WatermarkConfigChange.Typeface(TextTypeface.Bold), WatermarkConfigChange.from(FuncType.TextTypeFace, TextTypeface.Bold))
        assertEquals(WatermarkConfigChange.TileMode(WatermarkTileMode.REPEAT), WatermarkConfigChange.from(FuncType.TileMode, WatermarkTileMode.REPEAT))
    }

    @Test
    fun horizontal_and_vertical_gaps_round_to_int() {
        // Matches the legacy `(any as Float).roundToInt()` passed to updateHorizon/updateVertical.
        assertEquals(WatermarkConfigChange.HorizontalGap(13), WatermarkConfigChange.from(FuncType.Horizon, 12.7f))
        assertEquals(WatermarkConfigChange.HorizontalGap(12), WatermarkConfigChange.from(FuncType.Horizon, 12.4f))
        assertEquals(WatermarkConfigChange.VerticalGap(13), WatermarkConfigChange.from(FuncType.Vertical, 12.5f)) // half-up
        assertEquals(WatermarkConfigChange.VerticalGap(0), WatermarkConfigChange.from(FuncType.Vertical, 0.2f))
    }

    @Test
    fun wrong_value_type_is_fail_fast() {
        // Preserves the old `any as X` fail-fast behavior (ClassCastException), not silent ignore.
        assertFailsWith<ClassCastException> { WatermarkConfigChange.from(FuncType.Text, 5) }
        assertFailsWith<ClassCastException> { WatermarkConfigChange.from(FuncType.Alpha, "nope") }
        assertFailsWith<ClassCastException> { WatermarkConfigChange.from(FuncType.Horizon, 7) } // Int, not Float
    }
}
