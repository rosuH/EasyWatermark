package me.rosuh.easywatermark.data.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
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
 * SCOPE (S4d-231): **empty-store** builder only — Room creates the schema on first open. Locale-aware
 * seeding from a bundled `.db` (the Desktop [TemplateDatabaseSeeds] analogue) is a deferred follow-up, as
 * it needs a bundled NSBundle seed asset + Xcode packaging. The DB file is `ewm-db` under the caller-
 * supplied [dir]. Schema (`Template`, version 1, `exportSchema=false`) is the unchanged commonMain one;
 * the per-target impl is the KSP-generated [AppDatabaseConstructor].
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
 * S4d-231: production no-arg overload — builds the iOS template DB under the app's `NSDocumentDirectory`
 * (the same store-location convention as `CreateDataStore.ios.kt`). Single-instance-per-file: a real iOS
 * consumer retains one database. The parameterized [buildTemplateDatabase] above is the test seam (the
 * roundtrip test passes a unique temp dir); this overload is just the NSDocumentDirectory path resolution.
 */
@OptIn(ExperimentalForeignApi::class)
fun buildTemplateDatabase(): AppDatabase = buildTemplateDatabase(iosDocumentsDirectory())

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
