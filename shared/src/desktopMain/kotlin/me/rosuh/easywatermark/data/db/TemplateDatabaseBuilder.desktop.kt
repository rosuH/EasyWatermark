package me.rosuh.easywatermark.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * S4d-142: Desktop (JVM) creation of the commonMain [AppDatabase] — the off-Android analogue of
 * androidMain `buildTemplateDatabase(context)`.
 *
 * Unlike Android (Room **compatibility mode** on the framework SupportSQLite open-helper, no driver),
 * Room on JVM/Desktop **requires an explicit `SQLiteDriver`** — there is no compatibility mode off
 * Android. This uses [BundledSQLiteDriver] from `androidx.sqlite:sqlite-bundled`, a **desktopMain-only**
 * dependency that ships a host-native `libsqliteJni`; it must NOT reach `:app` (verified by the S4d-142
 * `:app` no-native-leak check). The query coroutine context is `Dispatchers.IO`, matching the Android
 * `TemplateRepository` threading.
 *
 * SCOPE (S4d-142 / S4d-224): the builder supports both an **empty** store (original S4d-142 behavior)
 * and an optional seeded store. Because Room KMP's `createFromFile`/`createFromAsset` APIs are not
 * available off-Android, seeding is performed by copying the seed file to the DB path before Room opens
 * it; Room validates the `room_master_table` identity hash. The Android prepackaged `ewm-db-{ch,eng}.db`
 * assets are the seed source; the English seed is used for Desktop default seeding (locale-aware selection
 * is deferred). Room creates the schema on first open when no seed is supplied; the DB file is `ewm-db`
 * under the caller-supplied [dir]. Schema (`Template`, version 1, `exportSchema=false`) is the unchanged
 * commonMain one — the per-target impl comes from the KSP-generated [AppDatabaseConstructor].
 */
/**
 * S4d-224: build an empty Desktop template database. This existing signature is preserved so tests and
 * callers that explicitly want an empty store keep working unchanged.
 */
fun buildTemplateDatabase(dir: File): AppDatabase = buildTemplateDatabase(dir, seedFile = null)

/**
 * S4d-224: build a Desktop template database, optionally seeded from [seedFile].
 *
 * - When [seedFile] is non-null and the target DB file does not yet exist, the seed file is copied to the
 *   DB path before Room opens it. Room then validates the `room_master_table` identity hash and opens the
 *   database as a normal pre-existing DB. This is the desktop analogue of Android's `createFromFile`, which
 *   is not available in the Room KMP runtime.
 * - When [seedFile] is null, Room creates an empty schema (the original S4d-142 behavior).
 *
 * The seed file must be a valid SQLite database matching the commonMain Room schema. If the DB file already
 * exists, the seed file is ignored, so repeated calls are idempotent and user edits are preserved.
 */
fun buildTemplateDatabase(dir: File, seedFile: File?): AppDatabase {
    if (!dir.exists()) dir.mkdirs()
    val dbFile = File(dir, "ewm-db")
    if (seedFile != null && !dbFile.exists()) {
        seedFile.copyTo(dbFile, overwrite = false)
    }
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile.absolutePath,
        factory = AppDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
