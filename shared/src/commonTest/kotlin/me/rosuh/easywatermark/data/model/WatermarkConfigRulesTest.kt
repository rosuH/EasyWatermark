package me.rosuh.easywatermark.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the platform-neutral [WatermarkConfigRules] (S4d-61) to the exact behavior that was previously
 * inlined in Android `WaterMarkRepository`/`MainViewModel`, so the move stays byte-identical.
 */
class WatermarkConfigRulesTest {

    @Test
    fun alpha_byte_clamps_to_0_255() {
        assertEquals(0, WatermarkConfigRules.clampAlphaByte(-1))
        assertEquals(0, WatermarkConfigRules.clampAlphaByte(0))
        assertEquals(128, WatermarkConfigRules.clampAlphaByte(128))
        assertEquals(255, WatermarkConfigRules.clampAlphaByte(255))
        assertEquals(255, WatermarkConfigRules.clampAlphaByte(300))
    }

    @Test
    fun alpha_percent_to_byte_matches_legacy_float_truncation() {
        // (percent / 100 * 255).toInt()
        assertEquals(0, WatermarkConfigRules.alphaPercentToByte(0f))
        assertEquals(127, WatermarkConfigRules.alphaPercentToByte(50f)) // 127.5 -> 127 (truncated)
        assertEquals(255, WatermarkConfigRules.alphaPercentToByte(100f))
    }

    @Test
    fun horizontal_gap_clamps_to_0_max() {
        assertEquals(0, WatermarkConfigRules.clampHorizontalGap(-5))
        assertEquals(250, WatermarkConfigRules.clampHorizontalGap(250))
        assertEquals(WatermarkConfigRules.MAX_HORIZONTAL_GAP, WatermarkConfigRules.clampHorizontalGap(99999))
    }

    @Test
    fun vertical_gap_clamps_to_0_max() {
        assertEquals(0, WatermarkConfigRules.clampVerticalGap(-5))
        assertEquals(250, WatermarkConfigRules.clampVerticalGap(250))
        assertEquals(WatermarkConfigRules.MAX_VERTICAL_GAP, WatermarkConfigRules.clampVerticalGap(99999))
    }

    @Test
    fun degree_clamps_to_0_max() {
        assertEquals(0f, WatermarkConfigRules.clampDegree(-1f))
        assertEquals(180f, WatermarkConfigRules.clampDegree(180f))
        assertEquals(WatermarkConfigRules.MAX_DEGREE, WatermarkConfigRules.clampDegree(99999f))
    }

    @Test
    fun text_size_clamps_min_only_no_upper_bound() {
        assertEquals(WatermarkConfigRules.MIN_TEXT_SIZE, WatermarkConfigRules.clampTextSize(0.5f))
        assertEquals(14f, WatermarkConfigRules.clampTextSize(14f))
        // No upper clamp on storage today (MAX_TEXT_SIZE bounds only the editor slider).
        assertEquals(500f, WatermarkConfigRules.clampTextSize(500f))
    }

    @Test
    fun mode_rules_match_text_and_icon_updates() {
        assertEquals(WatermarkMode.Text, WatermarkConfigRules.MODE_ON_TEXT_UPDATE)
        assertEquals(WatermarkMode.Image, WatermarkConfigRules.MODE_ON_ICON_UPDATE)
        assertEquals(0, WatermarkConfigRules.MODE_ON_TEXT_UPDATE.value)
        assertEquals(1, WatermarkConfigRules.MODE_ON_ICON_UPDATE.value)
    }

    @Test
    fun limit_constants_unchanged() {
        assertEquals(1f, WatermarkConfigRules.MIN_TEXT_SIZE)
        assertEquals(100f, WatermarkConfigRules.MAX_TEXT_SIZE)
        assertEquals(14f, WatermarkConfigRules.DEFAULT_TEXT_SIZE)
        assertEquals(360f, WatermarkConfigRules.MAX_DEGREE)
        assertEquals(500, WatermarkConfigRules.MAX_HORIZONTAL_GAP)
        assertEquals(500, WatermarkConfigRules.MAX_VERTICAL_GAP)
    }
}
