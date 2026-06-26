package me.rosuh.easywatermark.data.db

import androidx.room.TypeConverter
import kotlin.time.Instant

/**
 * Room type converter for the `Template` timestamp columns.
 *
 * S4d-35: migrated from the legacy JVM date type to stdlib `kotlin.time.Instant` (Kotlin 2.3 stdlib;
 * no `kotlinx-datetime`). The on-disk representation is **unchanged**: the same `Long` epoch-milliseconds
 * stored in the existing `INTEGER` columns. The old `.time` getter and `Instant.toEpochMilliseconds()`
 * are both epoch-millis, so existing rows and the prepopulated DBs round-trip identically (DB version
 * stays 1).
 */
class DateConverter {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? {
        return value?.let { Instant.fromEpochMilliseconds(it) }
    }

    @TypeConverter
    fun dateToTimestamp(instant: Instant?): Long? {
        return instant?.toEpochMilliseconds()
    }
}
