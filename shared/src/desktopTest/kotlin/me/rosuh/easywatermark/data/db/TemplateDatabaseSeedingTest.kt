package me.rosuh.easywatermark.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.repo.TemplateRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S4d-224: Desktop template DB seeding via the shared desktopMain resource.
 *
 * Verifies that `unpackDefaultTemplateSeed` extracts the bundled English seed DB and that
 * `buildTemplateDatabase(dir, seedFile)` produces a database already populated with the seeded templates.
 * The existing empty-store path (`buildTemplateDatabase(dir)`) is intentionally exercised here too so both
 * creation modes are covered.
 */
class TemplateDatabaseSeedingTest {

    private val dir: File = Files.createTempDirectory("s4d224-template-seed").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun empty_store_path_still_works() = runBlocking {
        val db = buildTemplateDatabase(dir)
        try {
            val repo = TemplateRepository(db.templateDao(), Dispatchers.IO)
            assertFalse(repo.checkIfIsDaoNull(), "real DAO must be present")
            assertTrue(repo.getAllTemplate().first().isEmpty(), "empty-store builder starts empty")
        } finally {
            db.close()
        }
    }

    @Test
    fun seeded_database_contains_expected_templates() = runBlocking {
        val seedFile = File(dir, "seed.db")
        unpackDefaultTemplateSeed(seedFile)
        assertTrue(seedFile.isFile && seedFile.length() > 0, "seed file was unpacked")

        val db = buildTemplateDatabase(dir, seedFile)
        try {
            val repo = TemplateRepository(db.templateDao(), Dispatchers.IO)
            assertFalse(repo.checkIfIsDaoNull(), "real DAO must be present")

            val templates = repo.getAllTemplate().first()
            assertTrue(templates.isNotEmpty(), "seeded database must contain templates")
            assertEquals(1, templates.size, "English seed currently contains exactly one template")

            val first = templates.first()
            assertEquals(
                "Only for someone to apply something\nNot vaild for other use",
                first.content,
                "seeded template content matches the English seed asset"
            )
            assertTrue(first.id != 0, "seeded template has a valid row id")
        } finally {
            db.close()
        }
    }
}
