package me.rosuh.easywatermark.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.TemplateEditor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Desktop (JVM) templates roundtrip over the commonMain Room path, built by the new desktopMain
 * [buildTemplateDatabase] with the bundled SQLite driver. Empty store (no seeding). Exercises the existing
 * CommonMain [TemplateRepository] (`getAllTemplate`/`checkIfIsDaoNull`) + [TemplateEditor] (`add`/`delete`), * using plain `kotlinx.coroutines.runBlocking` — the existing `shared/src/desktopTest` roundtrip pattern
 * (`DesktopWaterMarkStoreRoundtripTest`, `UserConfigDataStoreRoundtripTest`); NO `kotlinx-coroutines-test`.
 */
class TemplateRoundtripTest {

    private val dir: File = Files.createTempDirectory("s4d142-template-db").toFile()
    private val db = buildTemplateDatabase(dir)
    private val repo = TemplateRepository(db.templateDao(), Dispatchers.IO)
    private val editor = TemplateEditor(repo)

    @AfterTest
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun empty_store_starts_empty_with_real_dao() = runBlocking {
        // A real bundled-driver DB (not the null-DB fallback) that starts with zero templates.
        assertFalse(repo.checkIfIsDaoNull(), "real DAO must be present (not the null-DB fallback)")
        assertTrue(repo.getAllTemplate().first().isEmpty(), "a freshly built empty store has no templates")
    }

    @Test
    fun add_list_delete_roundtrip() = runBlocking {
        editor.add("S4d-142 roundtrip")
        val afterAdd = repo.getAllTemplate().first()
        assertEquals(1, afterAdd.size, "exactly one template after add")
        assertEquals("S4d-142 roundtrip", afterAdd[0].content, "the inserted content round-trips")
        assertTrue(afterAdd[0].id != 0, "autoGenerate assigned a row id")

        editor.delete(afterAdd[0])
        assertTrue(repo.getAllTemplate().first().isEmpty(), "store is empty again after delete")
    }

    @Test
    fun update_preserves_id_and_creation_date() = runBlocking {
        editor.add("original content")
        val afterAdd = repo.getAllTemplate().first()
        assertEquals(1, afterAdd.size, "exactly one template after add")

        val original = afterAdd[0]
        val originalId = original.id
        val originalCreationDate = original.creationDate
        assertTrue(originalId != 0, "autoGenerate assigned a row id")

        editor.update(original.copy(content = "updated content", lastModifiedDate = Clock.System.now()))
        val afterUpdate = repo.getAllTemplate().first()
        assertEquals(1, afterUpdate.size, "still exactly one template after update")
        assertEquals(originalId, afterUpdate[0].id, "update preserves the row id")
        assertEquals("updated content", afterUpdate[0].content, "update changes the content")
        assertEquals(originalCreationDate, afterUpdate[0].creationDate, "update preserves the creation date")
    }
}
