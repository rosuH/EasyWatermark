package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Storage
import androidx.datastore.preferences.core.Preferences

/**
 * Platform-neutral, driver-free DataStore creation seam (S4d-74).
 *
 * This is intentionally NOT a `commonMain expect`: this module targets androidTarget + desktop +
 * iosArm64 + iosSimulatorArm64, and an `expect` would require an `actual` on every target while only
 * Android has a consumer today (it would break the `:shared` desktop/iOS compile gates). So the
 * common code is a plain helper, and Android store creation is a plain `androidMain` function
 * (`createPreferencesDataStore`), no `actual`.
 *
 * The helper takes an already-built [Storage] so it stays free of any platform file-system / decode
 * API. Desktop/iOS will build their [Storage] (e.g. okio) and call this in a later slice; that is
 * when the true `expect/actual` promotion happens. Android does NOT route through this helper yet:
 * reproducing the legacy store byte-for-byte needs `PreferenceDataStoreFactory.create(produceFile,
 * migrations)` (the only public API that pairs the exact `preferencesDataStoreFile` path with the
 * `SharedPreferencesMigration`); building a byte-identical `Storage<Preferences>` would need the
 * internal preferences serializer. See androidMain `CreateDataStore.android.kt`.
 */
fun createDataStore(storage: Storage<Preferences>): DataStore<Preferences> =
    DataStoreFactory.create(storage = storage)
