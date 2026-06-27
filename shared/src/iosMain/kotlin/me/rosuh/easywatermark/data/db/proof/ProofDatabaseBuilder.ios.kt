package me.rosuh.easywatermark.data.db.proof

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * S4d-91 proof-only iOS builder. Differs from the other platforms only in how the DB file path is
 * obtained (NSDocumentDirectory); the driver stays the common BundledSQLiteDriver() via
 * [getProofDatabase]. Foundation interop only — no new dependency, no iOS app wiring.
 */
@OptIn(ExperimentalForeignApi::class)
fun proofDatabaseBuilder(): RoomDatabase.Builder<ProofDatabase> {
    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    val dbFilePath = requireNotNull(documentDirectory).path + "/s4d91_proof.db"
    return Room.databaseBuilder<ProofDatabase>(
        name = dbFilePath,
    )
}
