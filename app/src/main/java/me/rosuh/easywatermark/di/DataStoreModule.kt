package me.rosuh.easywatermark.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import me.rosuh.easywatermark.data.datastore.createPreferencesDataStore
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository

/**
 * Store creation now lives in `:shared/androidMain` ([createPreferencesDataStore]), preserving * the exact legacy file path (`filesDir/datastore/<name>.preferences_pb`) + `SharedPreferencesMigration`.
 * These extension properties keep the same names/types so consumers (`RepositoryModule`) are unchanged,
 * and back them with a process-wide single instance per file — the old `by preferencesDataStore(...)`
 * delegate's guarantee (DataStore forbids a second active store for the same file). Keyed on the
 * application context.
 */
private val storeLock = Any()

@Volatile
private var userStore: DataStore<Preferences>? = null

@Volatile
private var waterMarkStore: DataStore<Preferences>? = null

val Context.userDataStore: DataStore<Preferences>
    get() = userStore ?: synchronized(storeLock) {
        userStore ?: createPreferencesDataStore(applicationContext, UserConfigRepository.SP_NAME)
            .also { userStore = it }
    }

val Context.waterMarkDataStore: DataStore<Preferences>
    get() = waterMarkStore ?: synchronized(storeLock) {
        waterMarkStore ?: createPreferencesDataStore(applicationContext, WaterMarkRepository.SP_NAME)
            .also { waterMarkStore = it }
    }
