package me.rosuh.easywatermark.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4d-32: compile/link proof for the iOS byte-array bulk-copy bridge ([IosByteArrayInterop]).
 *
 * Like the other `iosTest` suites, these RUN only on an iOS runtime (`iosSimulatorArm64Test`), which is
 * not installed here — so this slice's proof is **compile + native test-executable LINK**; the asserts
 * RUN at C5.3. They exercise the byte-exactness contract that matters for image bytes: every value
 * survives the `ByteArray → NSData → ByteArray` round-trip, including the signed-`Byte` edge cases.
 */
class IosByteArrayInteropTest {

    @Test
    fun round_trips_signed_byte_edge_values() {
        // 0x80/0xFF/0xFE are negative as Kotlin signed `Byte`; a wrong (re)interpretation would corrupt them.
        val original = byteArrayOf(
            0x00, 0x01, 0x7F, 0x80.toByte(), 0xFE.toByte(), 0xFF.toByte(),
        )
        val data = IosByteArrayInterop.toNSData(original)
        assertEquals(original.size, data.length.toInt(), "NSData length must match source size")
        val back = IosByteArrayInterop.fromNSData(data)
        assertTrue(original.contentEquals(back), "every byte (incl. 0x00/0x7F/0x80/0xFF) must round-trip exactly")
    }

    @Test
    fun round_trips_all_256_byte_values() {
        val original = ByteArray(256) { it.toByte() } // 0x00..0xFF in order
        val back = IosByteArrayInterop.fromNSData(IosByteArrayInterop.toNSData(original))
        assertTrue(original.contentEquals(back), "all 256 byte values must round-trip")
    }

    @Test
    fun empty_round_trips_to_empty() {
        val empty = ByteArray(0)
        val data = IosByteArrayInterop.toNSData(empty)
        assertEquals(0, data.length.toInt(), "empty input must produce zero-length NSData")
        assertEquals(0, IosByteArrayInterop.fromNSData(data).size, "empty NSData must decode to empty ByteArray")
    }

    @Test
    fun larger_buffer_round_trips_exactly() {
        // A deterministic non-trivial buffer (no Random — keeps the test reproducible).
        val original = ByteArray(4096) { (((it * 37) xor (it ushr 3)) and 0xFF).toByte() }
        val back = IosByteArrayInterop.fromNSData(IosByteArrayInterop.toNSData(original))
        assertEquals(original.size, back.size, "size must match")
        assertTrue(original.contentEquals(back), "a 4 KB buffer must round-trip byte-for-byte")
    }
}
