package me.rosuh.easywatermark.data.db.proof

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * S4d-91 proof-only desktop (JVM) builder. Differs from the other platforms only in how the DB file
 * path is obtained (a plain java.io.File); the driver stays the common BundledSQLiteDriver() via
 * [getProofDatabase]. Not wired into any desktop product UI.
 */
fun proofDatabaseBuilder(dbFile: File): RoomDatabase.Builder<ProofDatabase> =
    Room.databaseBuilder<ProofDatabase>(
        name = dbFile.absolutePath,
    )
