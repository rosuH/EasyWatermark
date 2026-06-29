package me.rosuh.easywatermark.data.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.TemplateEditor
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * S4d-231: iOS runtime proof that the commonMain Room templates path RUNS on iOS via the new iosMain
 * [buildTemplateDatabase] (bundled SQLite driver). Empty store (no seeding). Exercises the existing
 * commonMain [TemplateRepository] (`checkIfIsDaoNull`/`getAllTemplate`) + [TemplateEditor]
 * (`isDaoNull`/`add`/`update`/`delete`), mirroring the desktopTest `TemplateRoundtripTest` with plain
 * `runBlocking` (no `kotlinx-coroutines-test`). RUNS on `iosSimulatorArm64Test`.
 *
 * A unique `NSUUID`-suffixed directory under `NSTemporaryDirectory()` avoids cross-run collisions in the
 * ephemeral simulator container (the builder hardcodes the DB file name `ewm-db`).
 */
class TemplateRoundtripTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun newEmptyDb(): AppDatabase {
        val dir = NSTemporaryDirectory() + "s4d231_template_" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return buildTemplateDatabase(dir)
    }

    @Test
    fun ios_empty_store_add_list_update_delete_roundtrip() = runBlocking {
        val db = newEmptyDb()
        try {
            val repo = TemplateRepository(db.templateDao(), Dispatchers.Default)
            val editor = TemplateEditor(repo)

            // A real bundled-driver DB (not the null-DB fallback) that starts with zero templates.
            assertFalse(editor.isDaoNull(), "real DAO must be present (not the null-DB fallback)")
            assertTrue(
                repo.getAllTemplate().first().isEmpty(),
                "a freshly built empty store has no templates",
            )

            editor.add("S4d-231 ios roundtrip")
            val afterAdd = repo.getAllTemplate().first()
            assertEquals(1, afterAdd.size, "exactly one template after add")
            assertEquals("S4d-231 ios roundtrip", afterAdd[0].content, "the inserted content round-trips")
            assertTrue(afterAdd[0].id != 0, "autoGenerate assigned a row id")

            val original = afterAdd[0]
            editor.update(original.copy(content = "updated content", lastModifiedDate = Clock.System.now()))
            val afterUpdate = repo.getAllTemplate().first()
            assertEquals(1, afterUpdate.size, "still exactly one template after update")
            assertEquals(original.id, afterUpdate[0].id, "update preserves the row id")
            assertEquals("updated content", afterUpdate[0].content, "update changes the content")
            assertEquals(
                original.creationDate,
                afterUpdate[0].creationDate,
                "update preserves the creation date",
            )

            editor.delete(afterUpdate[0])
            assertTrue(
                repo.getAllTemplate().first().isEmpty(),
                "store is empty again after delete",
            )
        } finally {
            db.close()
        }
    }
}
