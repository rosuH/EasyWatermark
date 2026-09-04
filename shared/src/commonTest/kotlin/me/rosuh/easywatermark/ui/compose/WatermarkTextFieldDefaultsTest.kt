package me.rosuh.easywatermark.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * H1/H8: form path must not silently force singleLine; shared defaults bind form ↔ sheet.
 */
class WatermarkTextFieldDefaultsTest {

    @Test
    fun form_path_is_multiline() {
        assertFalse(WatermarkTextFieldDefaults.singleLine)
        assertTrue(WatermarkTextFieldDefaults.formMinLines >= 3)
        assertTrue(WatermarkTextFieldDefaults.formMaxLines >= WatermarkTextFieldDefaults.formMinLines)
    }

    @Test
    fun sheet_path_matches_form_multiline_contract() {
        assertEquals(WatermarkTextFieldDefaults.singleLine, false)
        assertTrue(WatermarkTextFieldDefaults.sheetMinLines >= 3)
        assertTrue(WatermarkTextFieldDefaults.sheetMaxLines >= WatermarkTextFieldDefaults.sheetMinLines)
    }

    @Test
    fun summary_allows_multiline_affordance() {
        assertTrue(WatermarkTextFieldDefaults.summaryMaxLines >= 2)
    }
}
