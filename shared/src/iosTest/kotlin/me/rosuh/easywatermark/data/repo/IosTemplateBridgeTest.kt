package me.rosuh.easywatermark.data.repo

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.rosuh.easywatermark.data.db.buildTemplateDatabase
import me.rosuh.easywatermark.domain.TemplateEditor
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS runtime proof of the Swift-facing [IosTemplateBridge] over the common templates store.
 * RUNS on `iosSimulatorArm64Test`.
 *
 * The bridge is exercised over test-controlled DBs built with the parameterized `buildTemplateDatabase`
 * overloads (empty-store and seed-bytes), NOT the no-arg `buildTemplateDatabase()` — that path
 * reads the seed from `NSBundle.mainBundle`, which a Kotlin/Native test executable's bundle does not carry
 * (Kotlin/Native test bundle has no app Copy Bundle Resources). The no-arg seeded path used by `defaultIosTemplateBridge()` is proven by the
 * packaging gate (the seed ships in `iosApp.app`) + the live app.
 */
class IosTemplateBridgeTest {

    @OptIn(ExperimentalForeignApi::class)
    private fun uniqueDir(tag: String): String {
        val dir = NSTemporaryDirectory() + "s4d233_${tag}_" + NSUUID().UUIDString()
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        return dir
    }

    @Test
    fun bridge_add_list_delete_roundtrip() = runBlocking {
        val db = buildTemplateDatabase(uniqueDir("roundtrip"))
        try {
            val bridge = IosTemplateBridge(TemplateRepository(db.templateDao(), Dispatchers.Default))
            assertTrue(bridge.currentTemplates().isEmpty(), "empty store starts empty")

            bridge.addTemplate("ios bridge A")
            bridge.addTemplate("ios bridge B")
            val after = bridge.currentTemplates()
            assertEquals(2, after.size, "two templates after add")
            assertTrue(after.any { it.content == "ios bridge A" }, "content A round-trips")
            assertTrue(after.any { it.content == "ios bridge B" }, "content B round-trips")
            assertTrue(after.all { it.id != 0 }, "rows carry autoGenerate ids")

            val target = after.first { it.content == "ios bridge A" }
            bridge.deleteTemplate(target.id)
            val afterDelete = bridge.currentTemplates()
            assertEquals(1, afterDelete.size, "one template after delete")
            assertTrue(afterDelete.none { it.id == target.id }, "the deleted id is gone")
            assertTrue(afterDelete.any { it.content == "ios bridge B" }, "the other template remains")
        } finally {
            db.close()
        }
    }

    @Test
    fun bridge_reads_seeded_rows() = runBlocking {
        // Produce a valid seed DB (mirrors ), then prove the bridge reads seeded content.
        val seedDir = uniqueDir("seedsrc")
        val seedDb = buildTemplateDatabase(seedDir)
        try {
            TemplateEditor(TemplateRepository(seedDb.templateDao(), Dispatchers.Default)).add("seeded template")
        } finally {
            seedDb.close()
        }
        val seedBytes = FileSystem.SYSTEM.read("$seedDir/ewm-db".toPath()) { readByteArray() }

        val db = buildTemplateDatabase(uniqueDir("seeded"), seedBytes = seedBytes)
        try {
            val bridge = IosTemplateBridge(TemplateRepository(db.templateDao(), Dispatchers.Default))
            val rows = bridge.currentTemplates()
            assertEquals(1, rows.size, "the seeded row is visible through the bridge")
            assertTrue(rows.any { it.content == "seeded template" }, "seeded content reads back")
        } finally {
            db.close()
        }
    }
}
