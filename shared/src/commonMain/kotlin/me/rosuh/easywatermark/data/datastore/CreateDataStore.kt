package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Platform-neutral, driver-free DataStore creation seam.
 *
 * This is intentionally NOT a `commonMain expect`: this module targets androidTarget + desktop +
 * IosArm64 + iosSimulatorArm64, and an `expect` would require an `actual` on every target. The * platform store-creation functions have genuinely different signatures (Android needs a `Context`
 * + `SharedPreferencesMigration`; desktop/iOS only need a file path), so plain per-platform
 * functions are cleaner and more honest than a forced `expect`/`actual`.
 *
 * [createDataStore] takes an already-built [Storage] so it stays free of any platform file-system
 * API. Android does NOT route through it: reproducing the legacy store byte-for-byte needs
 * `PreferenceDataStoreFactory.create(produceFile, migrations)` (the only public API that pairs the
 * exact `preferencesDataStoreFile` path with the `SharedPreferencesMigration`); building a
 * byte-identical `Storage<Preferences>` would need the internal preferences serializer. See
 * androidMain `CreateDataStore.android.kt`.
 */
fun createDataStore(storage: Storage<Preferences>): DataStore<Preferences> =
    DataStoreFactory.create(storage = storage)

/**
 * G2 DataStore corruption policy (Desktop/iOS okio path stores):
 * 1. On [CorruptionException], quarantine the corrupt prefs file by renaming it to
 *    `<name>.preferences_pb.corrupt-<epochMs>` beside the original (best-effort).
 * 2. Return [emptyPreferences] so the store recovers with product defaults.
 *
 * Android production creation stays on its own factory + SharedPreferencesMigration path
 * (byte-compat residual — not wired here to avoid migration risk).
 */
fun preferencesCorruptionHandler(
    producePath: () -> Path,
): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { ex: CorruptionException ->
        quarantineCorruptPreferencesFile(producePath())
        // Swallow after quarantine: empty prefs = product defaults on next read.
        emptyPreferences()
    }

/**
 * Best-effort quarantine of a corrupt preferences file. Failures are ignored so recovery
 * can still return empty defaults. Visible for unit tests.
 */
internal fun quarantineCorruptPreferencesFile(
    path: Path,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    quarantineLabel: String = "corrupt",
) {
    runCatching {
        if (!fileSystem.exists(path)) return
        var quarantine = "${path}.$quarantineLabel".toPath()
        var n = 0
        while (fileSystem.exists(quarantine)) {
            n++
            quarantine = "${path}.$quarantineLabel-$n".toPath()
        }
        // Prefer atomic rename; fall back to copy+delete.
        runCatching {
            fileSystem.atomicMove(path, quarantine)
        }.recoverCatching {
            fileSystem.copy(path, quarantine)
            fileSystem.delete(path)
        }
    }
}

/**
 * Path-based preferences DataStore creation. Public, serializer-free common API
 * ([PreferenceDataStoreFactory.createWithPath]); the okio [Path] is supplied by platform code
 * (desktop/iOS). G2 wires [preferencesCorruptionHandler] by default.
 */
fun createPreferencesDataStore(producePath: () -> Path): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        corruptionHandler = preferencesCorruptionHandler(producePath),
        produceFile = producePath,
    )
