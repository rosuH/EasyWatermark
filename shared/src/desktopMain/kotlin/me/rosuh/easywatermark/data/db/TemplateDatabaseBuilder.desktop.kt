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
 * SCOPE (S4d-142): an **empty** store — NO `createFromAsset`/`createFromFile` seeding. The Android
 * prepackaged `ewm-db-{ch,eng}.db` assets are deferred (S4d-141 seed-db decision: Room validates the
 * schema identity hash on open, so seed parity needs its own gated slice). Room creates the schema on
 * first open; the DB file is `ewm-db` under the caller-supplied [dir]. Schema (`Template`, version 1,
 * `exportSchema=false`) is the unchanged commonMain one — the per-target impl comes from the KSP-generated
 * [AppDatabaseConstructor].
 */
fun buildTemplateDatabase(dir: File): AppDatabase {
    if (!dir.exists()) dir.mkdirs()
    return Room.databaseBuilder<AppDatabase>(
        name = File(dir, "ewm-db").absolutePath,
        factory = AppDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
