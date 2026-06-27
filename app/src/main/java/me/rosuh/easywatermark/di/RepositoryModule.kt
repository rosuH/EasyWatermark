package me.rosuh.easywatermark.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import me.rosuh.easywatermark.data.db.AppDatabase
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<UserConfigRepository> {
        UserConfigRepository(get<Context>().userDataStore)
    }

    single<WaterMarkRepository> {
        val context = get<Context>()
        WaterMarkRepository(context.waterMarkDataStore) {
            context.getString(R.string.config_default_water_mark_text)
        }
    }

    single<MemorySettingRepo> {
        MemorySettingRepo()
    }

    single<TemplateRepository> {
        TemplateRepository((getOrNull<AppDatabase>())?.templateDao())
    }

}