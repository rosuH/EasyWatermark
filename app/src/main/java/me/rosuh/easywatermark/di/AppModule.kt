package me.rosuh.easywatermark.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.rosuh.easywatermark.data.db.AppDatabase
import java.util.Locale
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideYourDatabase(
        @ApplicationContext app: Context
    ): AppDatabase? {
        val builder = Room.databaseBuilder(
            app,
            AppDatabase::class.java,
            "ewm-db"
        )
        val isCh = Locale.getDefault().language.contains("zh")
        builder.createFromAsset(if (isCh) "ewm-db-ch.db" else "ewm-db-eng.db")
        try {
            return builder.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    @Singleton
    @Provides
    fun provideTemplateDao(db: AppDatabase?) = db?.templateDao()
}