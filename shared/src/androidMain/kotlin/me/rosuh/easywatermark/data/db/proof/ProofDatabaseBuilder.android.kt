package me.rosuh.easywatermark.data.db.proof

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * S4d-91 proof-only Android builder. Differs from the other platforms only in how the DB file path
 * is obtained (Context.getDatabasePath); the driver stays the common BundledSQLiteDriver() via
 * [getProofDatabase]. Not wired into :app/Koin.
 */
fun proofDatabaseBuilder(context: Context): RoomDatabase.Builder<ProofDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath("s4d91_proof.db")
    return Room.databaseBuilder<ProofDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
}
