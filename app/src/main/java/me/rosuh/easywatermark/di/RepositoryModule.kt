package me.rosuh.easywatermark.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Named("UserPreferences")
    @Provides
    @Singleton
    fun provideUserRepository(dataStore: DataStore<Preferences>): UserConfigRepository {
        return UserConfigRepository(dataStore)
    }

    @Named("WaterMarkPreferences")
    @Provides
    @Singleton
    fun provideWaterMarkRepository(dataStore: DataStore<Preferences>): WaterMarkRepository {
        return WaterMarkRepository(dataStore)
    }

    @Named("WaterMarkPreferences")
    @Provides
    @Singleton
    fun provideMemorySettingRepository(): MemorySettingRepo {
        return MemorySettingRepo()
    }

    @Provides
    fun provideTemplateRepository(dao: TemplateDao?): TemplateRepository {
        return TemplateRepository(dao)
    }
}