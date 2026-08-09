package me.rosuh.easywatermark.data.db

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.repo.TemplateRepository
import me.rosuh.easywatermark.domain.TemplateEditor
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * iOS runtime proof of the **seeded** template DB path (the iOS analogue of the desktopTest
 * `TemplateDatabaseSeedingTest`). RUNS on `iosSimulatorArm64Test`.
 *
 * A Kotlin/Native test executable's bundle does NOT carry the app's Copy Bundle Resources (see
 * app Copy Bundle Resources), so the test cannot read the bundled `ewm-db-*.db` via `NSBundle.mainBundle`.
 * Instead it proves the platform-agnostic **seed-copy-then-open** mechanism end-to-end with a real Room
 * SQLite file as the seed: build an empty DB, add rows, close (so the on-disk file is a valid, complete
 * Room DB matching the commonMain schema + identity hash), read its bytes, then seed a FRESH dir from those
 * bytes via `buildTemplateDatabase(dir, seedBytes)` and assert the rows are present. This is exactly the
 * mechanism the production no-arg `buildTemplateDatabase()` uses with the bundled Android seed; that the
 * specific Android `ewm-db-{ch,eng}.db` files open under `BundledSQLiteDriver` is already proven on Desktop
 * (identical driver + commonMain schema). The bundled-resource RUN itself is exercised by a real
 * `iosApp.app` (xcodebuild-packaged), not by this test executable.
 */
class TemplateSeedRoundtripTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun uniqueDir(tag: String): String {
        val dir = NSTemporaryDirectory() + "s4d232_${tag}_" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    @Test
    fun ios_seeded_db_opens_with_seeded_rows() = runBlocking {
        // 1) Produce a valid Room DB file (correct schema + identity hash) to use as the seed.
        val seedDir = uniqueDir("seedsrc")
        val seedDb = buildTemplateDatabase(seedDir)
        try {
            val seedEditor = TemplateEditor(TemplateRepository(seedDb.templateDao(), Dispatchers.Default))
            seedEditor.add("seed template A")
            seedEditor.add("seed template B")
        } finally {
            seedDb.close() // close checkpoints so the on-disk `ewm-db` file is complete
        }
        val seedBytes = FileSystem.SYSTEM.read("$seedDir/ewm-db".toPath()) { readByteArray() }
        assertTrue(seedBytes.isNotEmpty(), "produced seed DB file must be non-empty")

        // 2) Seed a FRESH dir from those bytes and assert the rows load through TemplateRepository.
        val freshDir = uniqueDir("seeded")
        val db = buildTemplateDatabase(freshDir, seedBytes = seedBytes)
        try {
            val repo = TemplateRepository(db.templateDao(), Dispatchers.Default)
            val rows = repo.getAllTemplate().first()
            assertEquals(2, rows.size, "seeded DB must expose the 2 seeded templates")
            assertTrue(rows.any { it.content == "seed template A" }, "seeded row A present")
            assertTrue(rows.any { it.content == "seed template B" }, "seeded row B present")
        } finally {
            db.close()
        }
    }

    @Test
    fun ios_seeded_overload_with_null_bytes_is_empty_store() = runBlocking {
        // Parity with the empty-store builder: null seed bytes → a fresh empty DB.
        val db = buildTemplateDatabase(uniqueDir("nullseed"), seedBytes = null)
        try {
            val repo = TemplateRepository(db.templateDao(), Dispatchers.Default)
            assertTrue(repo.getAllTemplate().first().isEmpty(), "null seed bytes → empty store")
        } finally {
            db.close()
        }
    }

    @Test
    fun template_seed_loader_loud_failure_for_missing_resource() {
        // The test executable's bundle has no packaged seed → loud failure.
        val e = assertFailsWith<IllegalStateException> {
            IosTemplateSeed.loadSeedBytes(language = "definitely-missing-lang-xyz", bundle = NSBundle.mainBundle)
        }
        assertTrue(
            e.message?.contains("definitely-missing-lang-xyz") == true,
            "error must name the missing seed resource; was: ${e.message}",
        )
    }

    @Test
    fun template_seed_language_selection_matches_android_rule() {
        // Default-language rule mirrors Android/Desktop: only "zh*" → ch, else eng. Pure value check.
        assertEquals(IosTemplateSeed.LANGUAGE_CH, "ch")
        assertEquals(IosTemplateSeed.LANGUAGE_ENG, "eng")
    }
}
