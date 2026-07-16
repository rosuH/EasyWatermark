package me.rosuh.easywatermark.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.CoroutineContext

/**
 * S4d-231: iOS creation of the commonMain [AppDatabase] — the iOS analogue of the desktopMain
 * `buildTemplateDatabase` (S4d-142) and androidMain `buildTemplateDatabase(context)`.
 *
 * Like Desktop (and unlike Android's framework SupportSQLite **compatibility mode**), Room on iOS/Native
 * **requires an explicit `SQLiteDriver`** — there is no compatibility mode off Android. This uses
 * [BundledSQLiteDriver] from `androidx.sqlite:sqlite-bundled`, declared **iOS-target-only** in
 * `shared/build.gradle.kts` so it never reaches `:app` (the Android consumer uses the android variant,
 * which keeps compatibility mode).
 *
 * Query coroutine context: injected as [queryContext], defaulting to `Dispatchers.Default`. Unlike the
 * Desktop builder, this does NOT use `Dispatchers.IO` because `Dispatchers.IO` is `internal` on the Native
 * target in kotlinx-coroutines 1.10.2 (the same reason the commonMain
 * [me.rosuh.easywatermark.data.repo.TemplateRepository] takes an injected `ioContext`). A real iOS consumer
 * may pass a dedicated dispatcher; the default keeps Room's query work off the calling coroutine.
 *
 * SCOPE (S4d-231): **empty-store** builder only — Room creates the schema on first open. The DB file is
 * `ewm-db` under the caller-supplied [dir]. Schema (`Template`, version 1, `exportSchema=false`) is the
 * unchanged commonMain one; the per-target impl is the KSP-generated [AppDatabaseConstructor].
 *
 * S4d-232: a [buildTemplateDatabase] overload now optionally **seeds** from raw `seedBytes` (the Android
 * seed DB bytes), and the production no-arg [buildTemplateDatabase] seeds from the bundled seed via
 * [IosTemplateSeed]. This empty-store overload is preserved for tests/callers that explicitly want an empty DB.
 */
fun buildTemplateDatabase(
    dir: String,
    queryContext: CoroutineContext = Dispatchers.Default,
): AppDatabase =
    Room.databaseBuilder<AppDatabase>(
        name = "$dir/ewm-db",
        factory = { AppDatabaseConstructor.initialize() },
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryContext)
        .build()

/**
 * S4d-232: build an iOS template DB, optionally **seeded** from [seedBytes] — the iOS analogue of the
 * desktopMain `buildTemplateDatabase(dir, seedFile)` (S4d-224). When [seedBytes] is non-null and the target
 * DB file (`$dir/ewm-db`) does not yet exist, the bytes are written to that path **before** Room opens it;
 * Room then validates the `room_master_table` identity hash and opens it as a pre-existing DB (the same
 * copy-then-open approach Desktop uses, because Room KMP off-Android has no `createFromAsset`). When the DB
 * file already exists the seed is ignored, so repeated calls are idempotent and user edits are preserved.
 * When [seedBytes] is null this is equivalent to the empty-store overload.
 *
 * [seedBytes] must be a valid SQLite DB matching the commonMain Room schema (the authoritative Android
 * `ewm-db-{ch,eng}.db`). The file write uses okio's `FileSystem.SYSTEM` (already an iosMain transitive dep
 * via DataStore okio); no new dependency.
 */
fun buildTemplateDatabase(
    dir: String,
    seedBytes: ByteArray?,
    queryContext: CoroutineContext = Dispatchers.Default,
): AppDatabase {
    val dbName = "$dir/ewm-db"
    if (seedBytes != null) {
        val dbPath = dbName.toPath()
        if (!FileSystem.SYSTEM.exists(dbPath)) {
            dbPath.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(dbPath) { write(seedBytes) }
        }
    }
    return Room.databaseBuilder<AppDatabase>(
        name = dbName,
        factory = { AppDatabaseConstructor.initialize() },
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryContext)
        .build()
}

/**
 * S4d-231/S4d-232: production no-arg overload — builds the iOS template DB under the app's
 * `NSDocumentDirectory` (the same store-location convention as `CreateDataStore.ios.kt`), **seeded** on first
 * creation from the bundled Android seed DB selected by locale ([IosTemplateSeed]). Single-instance-per-file:
 * a real iOS consumer retains one database. The parameterized [buildTemplateDatabase] overloads above are the
 * test seams (the roundtrip tests pass a unique temp dir and/or explicit seed bytes — a Kotlin/Native test
 * executable's bundle does not carry the app's Copy Bundle Resources, so this no-arg path is exercised only
 * in a real `iosApp.app`, where the seed resource is packaged).
 */
/**
 * Production no-arg builder — **process-wide singleton** so Swift workflow + CMP product root
 * never open two Room instances on the same `ewm-db` file (dual open → crash).
 */
@OptIn(ExperimentalForeignApi::class)
fun buildTemplateDatabase(): AppDatabase = IosTemplateDatabaseHolder.instance

private object IosTemplateDatabaseHolder {
    val instance: AppDatabase by lazy {
        buildTemplateDatabase(
            dir = iosDocumentsDirectory(),
            seedBytes = IosTemplateSeed.loadSeedBytes(IosTemplateSeed.defaultSeedLanguage()),
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun iosDocumentsDirectory(): String {
    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory).path ?: error("NSDocumentDirectory path is null")
}
