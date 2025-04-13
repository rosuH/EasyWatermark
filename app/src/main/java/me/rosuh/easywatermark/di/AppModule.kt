package me.rosuh.easywatermark.di

import androidx.room.Room
import me.rosuh.easywatermark.data.db.AppDatabase
import me.rosuh.easywatermark.ui.MainViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.Locale

val appModule = module {
    single<AppDatabase> {
        val builder = Room.databaseBuilder(
            get(),
            AppDatabase::class.java,
            "ewm-db"
        )
        val isCh = Locale.getDefault().language.contains("zh")
        builder.createFromAsset(if (isCh) "ewm-db-ch.db" else "ewm-db-eng.db")
        try {
            builder.build()
        } catch (e: Exception) {
            e.printStackTrace()
            Room.inMemoryDatabaseBuilder(
                get(),
                AppDatabase::class.java
            ).build()
        }
    }
    includes(repositoryModule)
    viewModel {
        MainViewModel(
            get(),
            get(),
            get(),
            get(),
        )
    }
}