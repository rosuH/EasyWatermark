package me.rosuh.easywatermark.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/**
 * Android preferences DataStore creation (S4d-74). A plain `androidMain` function (NOT an `actual`):
 * commonMain needs no `expect`, and the desktop/iOS targets need no store creation in this slice.
 *
 * Byte-for-byte equivalent to the legacy `Context.preferencesDataStore(name, produceMigrations = …)`
 * delegate this replaces: same on-disk file `filesDir/datastore/<name>.preferences_pb` (via
 * [preferencesDataStoreFile]) and the same [SharedPreferencesMigration]`(context, name)`. Pass the
 * caller's `SP_NAME` — `:shared` does not (and must not) know `:app`'s repository constants.
 *
 * Caller owns single-instance-per-file semantics (DataStore throws on a second active store for the
 * same file). The Koin `single` / cached extension property in `:app` provides that.
 */
fun createPreferencesDataStore(context: Context, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, name)),
        produceFile = { context.preferencesDataStoreFile(name) },
    )
