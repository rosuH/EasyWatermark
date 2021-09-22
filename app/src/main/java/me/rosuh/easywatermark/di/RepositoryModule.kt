package me.rosuh.easywatermark.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.rosuh.easywatermark.repo.UserConfigRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideUserRepository(dataStore: DataStore<Preferences>): UserConfigRepository {
        return UserConfigRepository(dataStore)
    }
}