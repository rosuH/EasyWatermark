package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path

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
 * Path-based preferences DataStore creation. Public, serializer-free common API
 * ([PreferenceDataStoreFactory.createWithPath]); the okio [Path] is supplied by platform code
 * (desktop/iOS). This is the common building block both okio-backed targets share — Android keeps
 * Its own `Context`/migration factory in androidMain. */
fun createPreferencesDataStore(producePath: () -> Path): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = producePath)
