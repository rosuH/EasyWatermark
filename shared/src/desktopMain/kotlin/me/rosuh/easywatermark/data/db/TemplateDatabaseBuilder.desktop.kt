package me.rosuh.easywatermark.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Desktop (JVM) creation of the commonMain [AppDatabase] — the off-Android analogue of
 * AndroidMain `buildTemplateDatabase(context)`. *
 * Unlike Android (Room **compatibility mode** on the framework SupportSQLite open-helper, no driver),
 * Room on JVM/Desktop **requires an explicit `SQLiteDriver`** — there is no compatibility mode off
 * Android. This uses [BundledSQLiteDriver] from `androidx.sqlite:sqlite-bundled`, a **desktopMain-only**
 * dependency that ships a host-native `libsqliteJni`; it must NOT reach `:app` (verified by the
 * `:app` no-native-leak check). The query coroutine context is `Dispatchers.IO`, matching the Android
 * `TemplateRepository` threading.
 *
 * SCOPE: the builder supports both an **empty** store (original
 * behavior) and an optional seeded store. Because Room KMP's `createFromFile`/`createFromAsset` APIs are
 * not available off-Android, seeding is performed by copying the seed file to the DB path before Room
 * opens it; Room validates the `room_master_table` identity hash. The Android prepackaged
 * `ewm-db-{ch,eng}.db` assets are the seed source; [TemplateDatabaseSeeds] selects the seed by JVM locale
 * (`ch` for `zh`, `eng` otherwise). Room creates the schema on first open when no seed is supplied; the DB
 * file is `ewm-db` under the caller-supplied [dir]. Schema (`Template`, version 1, exportSchema=true
 * for G2 schema JSON) is the unchanged commonMain one — the per-target impl comes from the
 * KSP-generated [AppDatabaseConstructor].
 */
/**
 * Build an empty Desktop template database. This existing signature is preserved so tests and * callers that explicitly want an empty store keep working unchanged.
 */
fun buildTemplateDatabase(dir: File): AppDatabase = buildTemplateDatabase(dir, seedFile = null)

/**
 * Build a Desktop template database, optionally seeded from [seedFile]. *
 * - When [seedFile] is non-null and the target DB file does not yet exist, the seed is installed via
 * [installSeedAtomically] (temp sibling → sync → atomic move) before Room opens it. Room then validates
 * the `room_master_table` identity hash and opens the database as a normal pre-existing DB. This is the
 * desktop analogue of Android's `createFromFile`, which is not available in the Room KMP runtime.
 * - When [seedFile] is null, Room creates an empty schema (the original behavior).
 *
 * The seed file must be a valid SQLite database matching the commonMain Room schema. If the DB file already
 * exists, the seed file is ignored, so repeated calls are idempotent and user edits are preserved.
 */
fun buildTemplateDatabase(dir: File, seedFile: File?): AppDatabase {
    if (!dir.exists()) dir.mkdirs()
    val dbFile = File(dir, "ewm-db")
    if (seedFile != null && !dbFile.exists()) {
        installSeedAtomically(seedFile = seedFile, dbFile = dbFile)
    }
    return Room.databaseBuilder<AppDatabase>(
        name = dbFile.absolutePath,
        factory = AppDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

/**
 * G2: first-create seed install — never leave a half-written public `ewm-db`.
 *
 * Copies [seedFile] to a sibling temp under [dbFile]'s parent, fsyncs, then atomically moves
 * to [dbFile]. On failure, temp is deleted and [dbFile] is left absent (or untouched if it
 * already existed — callers only invoke this when [dbFile] does not exist).
 *
 * Visible for unit fault tests.
 */
internal fun installSeedAtomically(
    seedFile: File,
    dbFile: File,
    beforeMove: () -> Unit = {},
) {
    require(seedFile.isFile) { "seed file missing or not a regular file: $seedFile" }
    val parent = requireNotNull(dbFile.parentFile) { "dbFile has no parent: $dbFile" }
    if (!parent.exists()) parent.mkdirs()
    val temp = File(parent, ".ewm-seed-${UUID.randomUUID()}.tmp")
    var moved = false
    try {
        seedFile.copyTo(temp, overwrite = true)
        FileOutputStream(temp, /* append = */ true).use { it.fd.sync() }
        beforeMove()
        try {
            Files.move(
                temp.toPath(),
                dbFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temp.toPath(),
                dbFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        moved = true
    } catch (t: Throwable) {
        runCatching { if (temp.exists()) temp.delete() }
        throw t
    } finally {
        if (!moved) {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }
}
