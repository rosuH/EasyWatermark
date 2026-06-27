package me.rosuh.easywatermark.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import me.rosuh.easywatermark.data.db.dao.TemplateDao
import me.rosuh.easywatermark.data.model.entity.Template

/**
 * S4d-92: the production templates Room database, moved from `:app` into `:shared/commonMain` at the
 * same FQN `me.rosuh.easywatermark.data.db.AppDatabase`. Schema is unchanged — entity [Template],
 * version 1, `exportSchema = false`, [DateConverter] type converters — so the prepopulated
 * `ewm-db-ch.db`/`ewm-db-eng.db` assets and existing rows open identically.
 *
 * The KMP-required `@ConstructedBy`/`RoomDatabaseConstructor` pattern lets the Room compiler generate
 * the per-target constructor (replacing JVM reflection). Android creation (locale `createFromAsset`
 * + in-memory fallback) lives in `androidMain` `TemplateDatabaseBuilder.android.kt`; desktop/iOS have
 * no production builder in this slice (they only compile/link the database type).
 */
@Database(entities = [Template::class], version = 1, exportSchema = false)
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
