package me.rosuh.easywatermark.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import org.koin.dsl.module


val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(
    name = UserConfigRepository.SP_NAME,
    produceMigrations = { ctx ->
        listOf(
            SharedPreferencesMigration(
                ctx,
                UserConfigRepository.SP_NAME
            )
        )
    }
)

val Context.waterMarkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = WaterMarkRepository.SP_NAME,
    produceMigrations = { ctx ->
        listOf(
            SharedPreferencesMigration(
                ctx,
                WaterMarkRepository.SP_NAME
            )
        )
    }
)