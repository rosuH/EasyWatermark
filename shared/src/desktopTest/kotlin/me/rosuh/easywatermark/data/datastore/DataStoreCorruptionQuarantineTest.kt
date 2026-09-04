package me.rosuh.easywatermark.data.datastore

import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G2: corrupt prefs file is quarantined (renamed aside) so recovery can start empty.
 */
class DataStoreCorruptionQuarantineTest {

    private val dir: File = Files.createTempDirectory("g2-ds-corrupt").toFile()

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun quarantineCorruptPreferencesFile_renamesAside_andRemovesOriginal() {
        val prefs = File(dir, "sp_water_mark_config.preferences_pb")
        prefs.writeBytes(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        val path = prefs.absolutePath.toPath()
        quarantineCorruptPreferencesFile(path, FileSystem.SYSTEM, quarantineLabel = "corrupt")
        assertFalse(prefs.exists(), "original corrupt file must be gone")
        val quarantined = dir.listFiles()?.filter {
            it.name.startsWith("sp_water_mark_config.preferences_pb.corrupt")
        }.orEmpty()
        assertTrue(quarantined.isNotEmpty(), "quarantine sidecar must exist")
        assertTrue(quarantined.single().length() == 4L)
    }

    @Test
    fun quarantine_missingFile_isNoOp() {
        val path = File(dir, "missing.preferences_pb").absolutePath.toPath()
        quarantineCorruptPreferencesFile(path)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
