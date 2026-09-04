package me.rosuh.easywatermark.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * proves the `kotlin.time.Instant` Room converter preserves the **exact epoch-millis** stored by
 * the previous `java.util.Date` converter — null/epoch-0/fixed-millis round-trips, so DB version 1 and the
 * prepopulated databases stay compatible (no schema/migration change).
 */
class DateConverterTest {

    private val converter = DateConverter()

    @Test
    fun null_round_trips_both_directions() {
        assertNull(converter.fromTimestamp(null))
        assertNull(converter.dateToTimestamp(null))
    }

    @Test
    fun epoch_zero_round_trips() {
        val instant = converter.fromTimestamp(0L)
        assertEquals(0L, converter.dateToTimestamp(instant))
    }

    @Test
    fun fixed_nonzero_millis_round_trips() {
        val millis = 1709898983123L
        val instant = converter.fromTimestamp(millis)
        assertEquals(millis, converter.dateToTimestamp(instant))
    }

    @Test
    fun storage_preserves_exact_millis_for_representative_values() {
        // Same epoch-millis the old Date converter stored: dateToTimestamp(fromTimestamp(x)) == x.
        for (x in listOf(0L, 1L, -1L, 1000L, 1709898983123L, Long.MAX_VALUE / 2, Long.MIN_VALUE / 2)) {
            assertEquals(x, converter.dateToTimestamp(converter.fromTimestamp(x)))
        }
    }
}
