package me.rosuh.easywatermark.data.db.proof

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S4d-91 proof: the Room KMP toolchain not only compiles but the generated DB opens, inserts, and
 * reads back on the JVM via BundledSQLiteDriver(). Desktop-only, throwaway proof DB in a temp file.
 */
class ProofDatabaseRoundtripTest {

    private val dbFile: File = File.createTempFile("s4d91_proof", ".db").apply { delete() }

    @AfterTest
    fun cleanup() {
        dbFile.delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }

    @Test
    fun insertAndReadBack() = runBlocking {
        val db = getProofDatabase(proofDatabaseBuilder(dbFile))
        try {
            val dao = db.proofDao()
            assertEquals(0, dao.count())
            dao.insert(ProofItem(label = "a"))
            dao.insert(ProofItem(label = "b"))
            assertEquals(2, dao.count())
            assertEquals(listOf("a", "b"), dao.all().map { it.label })
        } finally {
            db.close()
        }
    }
}
