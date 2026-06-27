package me.rosuh.easywatermark.data.db.proof

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * S4d-91 Room KMP toolchain proof — NOT production.
 *
 * A throwaway commonMain Room database that exists only to prove the Room Gradle plugin +
 * KSP-multiplatform + sqlite-bundled toolchain compiles/links in :shared for android, desktop,
 * iosArm64, and iosSimulatorArm64. It is never wired into :app/Koin and shares nothing with the
 * production templates path (Template/AppDatabase live in :app, untouched). Drop this whole
 * package once the production move lands.
 */
@Entity
data class ProofItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
)

@Dao
interface ProofDao {
    @Insert
    suspend fun insert(item: ProofItem)

    @Query("SELECT COUNT(*) FROM ProofItem")
    suspend fun count(): Int

    @Query("SELECT * FROM ProofItem ORDER BY id ASC")
    suspend fun all(): List<ProofItem>
}

@Database(entities = [ProofItem::class], version = 1, exportSchema = false)
@ConstructedBy(ProofDatabaseConstructor::class)
abstract class ProofDatabase : RoomDatabase() {
    abstract fun proofDao(): ProofDao
}

// The Room compiler generates the actual implementation per target.
@Suppress("KotlinNoActualForExpect")
expect object ProofDatabaseConstructor : RoomDatabaseConstructor<ProofDatabase> {
    override fun initialize(): ProofDatabase
}

/**
 * Common builder finalizer — every target funnels its platform builder through here so the driver
 * stays common: BundledSQLiteDriver() on all platforms (no platform-specific driver).
 *
 * No setQueryCoroutineContext() call: the IO dispatcher accessor is not public in commonMain on the
 * Native target at this coroutines version, and per the Room KMP contract the builder already
 * defaults its query context to the IO dispatcher when none is set — so this is equivalent and
 * common-safe.
 */
fun getProofDatabase(builder: RoomDatabase.Builder<ProofDatabase>): ProofDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .build()
