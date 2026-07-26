package me.rosuh.easywatermark.di

import me.rosuh.easywatermark.data.db.AppDatabase
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.platform.AndroidDynamicColorCapability
import me.rosuh.easywatermark.platform.DynamicColorCapability
import me.rosuh.easywatermark.ui.MainViewModel
import me.rosuh.easywatermark.ui.about.AboutViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<AppDatabase> {
        // Android creation moved to :shared androidMain (locale createFromAsset + in-memory
        // fallback preserved, byte-identical). See data/db/TemplateDatabaseBuilder.android.kt.
        buildTemplateDatabase(get())
    }
    includes(repositoryModule)
    single<DynamicColorCapability> {
        AndroidDynamicColorCapability()
    }
    viewModel {
        MainViewModel(get(), get(), get())
    }
    viewModel {
        AboutViewModel(get(), get())
    }
}
