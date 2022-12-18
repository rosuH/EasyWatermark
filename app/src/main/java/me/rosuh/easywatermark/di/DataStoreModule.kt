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
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Named("UserPreferences")
    @Singleton
    @Provides
    fun userDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.userDataStore
    }

    @Named("WaterMarkPreferences")
    @Singleton
    @Provides
    fun provideWaterMarkDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.waterMarkDataStore
    }
}

val Context.userDataStore: DataStore<Preferences> by preferencesDataStore(
    name = UserConfigRepository.SP_NAME,
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, UserConfigRepository.SP_NAME)) }
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