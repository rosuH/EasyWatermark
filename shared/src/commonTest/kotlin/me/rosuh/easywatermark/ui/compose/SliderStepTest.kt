package me.rosuh.easywatermark.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class SliderStepTest {

    @Test
    fun arrow_steps_by_one_default() {
        assertEquals(51f, sliderStepValue(50f, 0f..100f, step = null, deltaUnits = 1))
        assertEquals(49f, sliderStepValue(50f, 0f..100f, step = null, deltaUnits = -1))
    }

    @Test
    fun shift_steps_by_ten() {
        assertEquals(60f, sliderStepValue(50f, 0f..100f, step = null, deltaUnits = 10))
        assertEquals(40f, sliderStepValue(50f, 0f..100f, step = null, deltaUnits = -10))
    }

    @Test
    fun respects_custom_step_and_clamps() {
        assertEquals(40f, sliderStepValue(20f, 0f..100f, step = 20f, deltaUnits = 1))
        assertEquals(100f, sliderStepValue(100f, 0f..100f, step = null, deltaUnits = 5))
        assertEquals(0f, sliderStepValue(0f, 0f..100f, step = null, deltaUnits = -5))
    }
}
