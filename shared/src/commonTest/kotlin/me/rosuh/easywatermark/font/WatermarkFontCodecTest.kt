package me.rosuh.easywatermark.font

import me.rosuh.easywatermark.data.model.WatermarkFontRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatermarkFontCodecTest {

    @Test
    fun missing_key_is_default() {
        assertEquals(WatermarkFontRef.Default, WatermarkFontCodec.decode(null))
        assertEquals(WatermarkFontRef.Default, WatermarkFontCodec.decode(""))
    }

    @Test
    fun round_trip_default_system_imported() {
        val refs = listOf(
            WatermarkFontRef.Default,
            WatermarkFontRef.System("android", "sans-serif"),
            WatermarkFontRef.Imported("a".repeat(64)),
        )
        for (ref in refs) {
            val encoded = WatermarkFontCodec.encode(ref)
            assertEquals(ref, WatermarkFontCodec.decode(encoded), encoded)
        }
    }

    @Test
    fun garbage_and_bad_hash_are_unavailable() {
        assertIs<WatermarkFontRef.Unavailable>(WatermarkFontCodec.decode("{not json"))
        assertIs<WatermarkFontRef.Unavailable>(
            WatermarkFontCodec.decode(WatermarkFontCodec.encode(WatermarkFontRef.Imported("not-a-hash"))),
        )
    }

    @Test
    fun fingerprint_is_stable() {
        assertEquals("default", WatermarkFontRef.Default.fingerprint())
        assertTrue(WatermarkFontRef.System("ios", "Helvetica").fingerprint().contains("Helvetica"))
    }
}
