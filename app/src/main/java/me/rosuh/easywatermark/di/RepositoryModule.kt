package me.rosuh.easywatermark.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import me.rosuh.easywatermark.data.db.AppDatabase
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<UserConfigRepository> {
        UserConfigRepository(get<Context>().userDataStore)
    }

    single<WaterMarkRepository> {
        WaterMarkRepository(get<Context>().waterMarkDataStore)
    }

    single<MemorySettingRepo> {
        MemorySettingRepo()
    }

    single<TemplateRepository> {
        TemplateRepository((getOrNull<AppDatabase>())?.templateDao())
    }

}