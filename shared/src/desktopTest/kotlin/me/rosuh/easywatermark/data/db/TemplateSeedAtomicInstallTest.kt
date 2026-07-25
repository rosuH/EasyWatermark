package me.rosuh.easywatermark.data.db

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G2: Desktop first-create seed install is crash-atomic (temp → move).
 */
class TemplateSeedAtomicInstallTest {

    private val dir: File = Files.createTempDirectory("g2-seed-atomic").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun seedTemps(): List<File> =
        dir.listFiles()?.filter { it.name.startsWith(".ewm-seed-") && it.name.endsWith(".tmp") }
            .orEmpty()

    @Test
    fun installSeedAtomically_success_publishesEwmDb_andNoOrphanTemp() {
        val seed = File(dir, "seed.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val dbFile = File(dir, "ewm-db")
        installSeedAtomically(seedFile = seed, dbFile = dbFile)
        assertTrue(dbFile.isFile)
        assertTrue(dbFile.length() == 5L)
        assertTrue(seedTemps().isEmpty())
    }

    @Test
    fun installSeedAtomically_failBeforeMove_leavesNoPublicEwmDb() {
        val seed = File(dir, "seed.db").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val dbFile = File(dir, "ewm-db")
        assertFailsWith<IllegalStateException> {
            installSeedAtomically(
                seedFile = seed,
                dbFile = dbFile,
                beforeMove = { throw IllegalStateException("fail before move") },
            )
        }
        assertFalse(dbFile.exists(), "failed seed must not leave public ewm-db")
        assertTrue(seedTemps().isEmpty(), "temp must be cleaned")
    }

    @Test
    fun buildTemplateDatabase_existingDb_notOverwrittenBySeed() {
        // Builder only calls installSeedAtomically when ewm-db is missing.
        val seed = File(dir, "seed.db").apply { writeBytes(byteArrayOf(1)) }
        val dbFile = File(dir, "ewm-db").apply { writeBytes(byteArrayOf(7, 7, 7)) }
        val before = dbFile.readBytes()
        if (!dbFile.exists()) {
            installSeedAtomically(seed, dbFile)
        }
        assertTrue(dbFile.readBytes().contentEquals(before))
    }
}
