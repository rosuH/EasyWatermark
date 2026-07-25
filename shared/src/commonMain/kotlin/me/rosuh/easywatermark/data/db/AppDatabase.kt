package me.rosuh.easywatermark.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.model.entity.Template

/**
 * Room database for watermark templates (schema v1).
 *
 * Built per-platform (Android SupportSQLite; Desktop/iOS BundledSQLiteDriver + seed assets).
 */
// G2: exportSchema=true so v1 JSON is generated under shared/schemas/ (no entity/version change).
@Database(entities = [Template::class], version = 1, exportSchema = true)
@ConstructedBy(AppDatabaseConstructor::class)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun templateDao(): TemplateDao
}

// The Room compiler generates the actual implementation per target.
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
