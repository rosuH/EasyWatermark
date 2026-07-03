package me.rosuh.easywatermark.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextColorOptionTest {

    @Test
    fun parse_argb_hex_accepts_hash_and_plain_forms() {
        assertEquals(0xFFFFB800.toInt(), parseArgbHexColor("#FFFFB800"))
        assertEquals(0xFFFFB800.toInt(), parseArgbHexColor("FFFFB800"))
    }

    @Test
    fun parse_rgb_hex_adds_opaque_alpha() {
        assertEquals(0xFFFFB800.toInt(), parseArgbHexColor("#FFB800"))
        assertEquals(0xFF00D1FF.toInt(), parseArgbHexColor("00d1ff"))
    }

    @Test
    fun parse_rejects_signed_or_malformed_values() {
        assertNull(parseArgbHexColor("+FFB800"))
        assertNull(parseArgbHexColor("-FFB800"))
        assertNull(parseArgbHexColor("#XYZXYZ"))
        assertNull(parseArgbHexColor("#FFFFF"))
        assertNull(parseArgbHexColor(""))
    }

    @Test
    fun format_argb_hex_is_stable_uppercase() {
        assertEquals("#FFFFB800", formatArgbHexColor(0xFFFFB800.toInt()))
        assertEquals("#8000D1FF", formatArgbHexColor(0x8000D1FF.toInt()))
    }
}
