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
 * iOS creation of the commonMain [AppDatabase] — the iOS analogue of the desktopMain
 * `buildTemplateDatabase` and androidMain `buildTemplateDatabase(context)`.
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
 * SCOPE: **empty-store** builder only — Room creates the schema on first open. The DB file is
 * `ewm-db` under the caller-supplied [dir]. Schema (`Template`, version 1, exportSchema=true for G2)
 * is the unchanged commonMain one; the per-target impl is the KSP-generated [AppDatabaseConstructor].
 *
 * A [buildTemplateDatabase] overload optionally **seeds** from raw `seedBytes` (Android seed DB bytes),
 * and the production no-arg [buildTemplateDatabase] seeds from the bundled seed via [IosTemplateSeed].
 * First-create seed install is crash-atomic (temp → atomicMove). Empty-store overload preserved for tests.
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
 * Build an iOS template DB, optionally **seeded** from [seedBytes] — the iOS analogue of the
 * desktopMain `buildTemplateDatabase(dir, seedFile)`. When [seedBytes] is non-null and the target
 * DB file (`$dir/ewm-db`) does not yet exist, bytes are installed via [installSeedBytesAtomically]
 * (temp → atomicMove) before Room opens. Room then validates the `room_master_table` identity hash.
 * When the DB file already exists the seed is ignored (user edits preserved). Null [seedBytes]
 * is equivalent to the empty-store overload.
 *
 * [seedBytes] must be a valid SQLite DB matching the commonMain Room schema (Android
 * `ewm-db-{ch,eng}.db`). Uses okio [FileSystem.SYSTEM] (iosMain transitive via DataStore).
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
            installSeedBytesAtomically(dbPath = dbPath, seedBytes = seedBytes)
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
 * G2: first-create seed install from raw bytes — never leave a half-written public `ewm-db`.
 * Visible for unit fault tests.
 */
internal fun installSeedBytesAtomically(
    dbPath: okio.Path,
    seedBytes: ByteArray,
    beforeMove: () -> Unit = {},
) {
    require(seedBytes.isNotEmpty()) { "seedBytes must be non-empty" }
    val fs = FileSystem.SYSTEM
    dbPath.parent?.let { fs.createDirectories(it) }
    val tmp = "$dbPath.seed.tmp".toPath()
    var moved = false
    try {
        fs.write(tmp) { write(seedBytes) }
        beforeMove()
        fs.atomicMove(tmp, dbPath)
        moved = true
    } catch (t: Throwable) {
        runCatching { if (fs.exists(tmp)) fs.delete(tmp) }
        throw t
    } finally {
        if (!moved) {
            runCatching { if (fs.exists(tmp)) fs.delete(tmp) }
        }
    }
}

/**
 * / production no-arg overload — builds the iOS template DB under the app's
 * `NSDocumentDirectory` (the same store-location convention as `CreateDataStore.ios.kt`), **seeded** on first
 * Creation from the bundled Android seed DB selected by locale ([IosTemplateSeed]). Single-instance-per-file: * a real iOS consumer retains one database. The parameterized [buildTemplateDatabase] overloads above are the
 * test seams (the roundtrip tests pass a unique temp dir and/or explicit seed bytes — a Kotlin/Native test
 * executable's bundle does not carry the app's Copy Bundle Resources, so this no-arg path is exercised only
 * in a real `iosApp.app`, where the seed resource is packaged).
 */
/**
 * Production no-arg builder — **process-wide singleton** so Swift workflow + CMP product root
 * Never open two Room instances on the same `ewm-db` file (dual open → crash). */
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
