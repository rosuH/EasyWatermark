package me.rosuh.easywatermark.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.rosuh.easywatermark.data.repo.SP_NAME
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Singleton
    @Provides
    fun userDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.userDataStore
    }
}

val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SP_NAME,
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, SP_NAME)) }
)