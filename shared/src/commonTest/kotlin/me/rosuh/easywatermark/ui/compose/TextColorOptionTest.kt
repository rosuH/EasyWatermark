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
    fun parse_rejects_malformed_values() {
        // Paste normalize strips non-hex junk (+/-), so only true garbage / wrong length fail.
        assertNull(parseArgbHexColor("#XYZXYZ"))
        assertNull(parseArgbHexColor("#FFFFF"))
        assertNull(parseArgbHexColor(""))
        assertNull(parseArgbHexColor("not-hex"))
    }

    @Test
    fun parse_accepts_signed_prefix_after_normalize() {
        assertEquals(0xFFFFB800.toInt(), parseArgbHexColor("+FFB800"))
        assertEquals(0xFFFFB800.toInt(), parseArgbHexColor("-FFB800"))
    }

    @Test
    fun format_argb_hex_is_stable_uppercase() {
        assertEquals("#FFFFB800", formatArgbHexColor(0xFFFFB800.toInt()))
        assertEquals("#8000D1FF", formatArgbHexColor(0x8000D1FF.toInt()))
    }

    @Test
    fun normalize_paste_strips_hash_0x_and_spaces() {
        assertEquals("FFB800", normalizeArgbHexInput(" #ffb800 "))
        assertEquals("FFB800", normalizeArgbHexInput("0xFFb800"))
        assertEquals("AABBCC", normalizeArgbHexInput("#aabbcc"))
    }

    @Test
    fun parse_accepts_short_rgb() {
        // #F0A → #FF00AA
        assertEquals(0xFFFF00AA.toInt(), parseArgbHexColor("#F0A"))
    }

    @Test
    fun parse_rejects_after_normalize_empty() {
        assertNull(parseArgbHexColor("###"))
        assertNull(parseArgbHexColor("0x"))
    }
}

