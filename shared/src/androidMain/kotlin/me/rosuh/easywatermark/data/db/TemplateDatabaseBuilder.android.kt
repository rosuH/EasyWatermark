package me.rosuh.easywatermark.data.db

import android.content.Context
import androidx.room.Room
import java.util.Locale

/**
 * S4d-92: Android production creation of [AppDatabase], moved out of `AppModule` into `:shared`'s
 * androidMain so the database type can live in commonMain while its Android-only prepopulation stays
 * Android-side (Room KMP's prepackaged-DB APIs are Android-only, not commonMain).
 *
 * Behavior is byte-identical to the previous `AppModule` builder:
 * - DB file is `ewm-db` at the same on-disk path (`getDatabasePath("ewm-db")`).
 * - Locale-selected `createFromAsset`: `ewm-db-ch.db` for zh, else `ewm-db-eng.db`.
 * - On build failure, fall back to an in-memory database.
 * - **No `SQLiteDriver` is set**, so Room stays in compatibility mode on the framework SupportSQLite
 *   open-helper — exactly the engine the prepopulated assets were built/opened with today. This adds
 *   no `sqlite-bundled`/`sqlite-framework` native payload to the APK (see driver-decision artifact).
 */
fun buildTemplateDatabase(context: Context): AppDatabase {
    val builder = Room.databaseBuilder<AppDatabase>(
        context = context,
        name = context.getDatabasePath("ewm-db").absolutePath,
    )
    val isCh = Locale.getDefault().language.contains("zh")
    builder.createFromAsset(if (isCh) "ewm-db-ch.db" else "ewm-db-eng.db")
    return try {
        builder.build()
    } catch (e: Exception) {
        e.printStackTrace()
        Room.inMemoryDatabaseBuilder<AppDatabase>(context).build()
    }
}
