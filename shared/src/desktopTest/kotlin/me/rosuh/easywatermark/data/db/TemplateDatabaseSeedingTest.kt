package me.rosuh.easywatermark.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.TemplateEditor
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * /  Desktop template DB seeding via the shared desktopMain resources.
 *
 * Verifies that `unpackDefaultTemplateSeed` / `unpackTemplateSeed` extract the bundled seed DBs and that
 * `buildTemplateDatabase(dir, seedFile)` produces a database already populated with the seeded templates.
 * Both locale keys (`ch`, `eng`) and the existing empty-store path (`buildTemplateDatabase(dir)`) are
 * Exercised. Existing DB files are protected from reseeding. */
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
    fun english_seed_contains_expected_templates() = runBlocking {
        val seedFile = File(dir, "seed-eng.db")
        unpackTemplateSeed(seedFile, "eng")
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

    @Test
    fun chinese_seed_contains_expected_templates() = runBlocking {
        val seedFile = File(dir, "seed-ch.db")
        unpackTemplateSeed(seedFile, "ch")
        assertTrue(seedFile.isFile && seedFile.length() > 0, "Chinese seed file was unpacked")

        val db = buildTemplateDatabase(dir, seedFile)
        try {
            val repo = TemplateRepository(db.templateDao(), Dispatchers.IO)
            assertFalse(repo.checkIfIsDaoNull(), "real DAO must be present")

            val templates = repo.getAllTemplate().first()
            assertTrue(templates.isNotEmpty(), "Chinese seeded database must contain templates")

            val first = templates.first()
            assertNotEquals(
                "Only for someone to apply something\nNot vaild for other use",
                first.content,
                "Chinese seed content differs from the English seed asset"
            )
            assertTrue(first.id != 0, "seeded Chinese template has a valid row id")
        } finally {
            db.close()
        }
    }

    @Test
    fun existing_database_is_not_reseeded() = runBlocking {
        val seedFile = File(dir, "seed-eng.db")
        unpackTemplateSeed(seedFile, "eng")

        val db = buildTemplateDatabase(dir, seedFile)
        val repo = TemplateRepository(db.templateDao(), Dispatchers.IO)
        val seededCount = repo.getAllTemplate().first().size
        assertTrue(seededCount > 0, "initial seeded DB must contain templates")

        // Add a user-created template so we can detect whether a second build overwrites the DB.
        TemplateEditor(repo).add("user")
        val afterAddCount = repo.getAllTemplate().first().size
        assertEquals(seededCount + 1, afterAddCount, "user template was added")
        db.close()

        // Rebuilding with the same seed file must leave the existing DB untouched.
        val db2 = buildTemplateDatabase(dir, seedFile)
        try {
            val repo2 = TemplateRepository(db2.templateDao(), Dispatchers.IO)
            val afterRebuild = repo2.getAllTemplate().first()
            assertEquals(afterAddCount, afterRebuild.size, "existing DB must not be reseeded/overwritten")
            assertTrue(afterRebuild.any { it.content == "user" }, "user-created template must survive rebuild")
        } finally {
            db2.close()
        }
    }

    @Test
    fun default_seed_language_matches_locale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CHINESE)
            assertEquals("ch", defaultTemplateSeedLanguage(), "Chinese locale selects ch seed")

            Locale.setDefault(Locale.ENGLISH)
            assertEquals("eng", defaultTemplateSeedLanguage(), "English locale selects eng seed")

            Locale.setDefault(Locale("zh", "TW"))
            assertEquals("ch", defaultTemplateSeedLanguage(), "zh-TW locale also selects ch seed")
        } finally {
            Locale.setDefault(original)
        }
    }
}
