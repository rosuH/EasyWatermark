package me.rosuh.easywatermark.data.db

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G2: iOS first-create seed install is crash-atomic (temp → atomicMove).
 */
class IosSeedAtomicInstallTest {

    private fun uniqueDir(): String {
        val base = NSTemporaryDirectory().trimEnd('/')
        return "$base/g2-seed-${NSUUID().UUIDString}"
    }

    @Test
    fun installSeedBytesAtomically_success_publishesEwmDb() {
        val dir = uniqueDir()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir.toPath())
        val dbPath = "$dir/ewm-db".toPath()
        installSeedBytesAtomically(dbPath, byteArrayOf(1, 2, 3, 4))
        assertTrue(fs.exists(dbPath))
        val tmp = "$dir/ewm-db.seed.tmp".toPath()
        assertFalse(fs.exists(tmp), "temp must not remain after success")
        // cleanup
        runCatching { fs.delete(dbPath) }
        runCatching { fs.delete(dir.toPath()) }
    }

    @Test
    fun installSeedBytesAtomically_failBeforeMove_leavesNoPublicEwmDb() {
        val dir = uniqueDir()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir.toPath())
        val dbPath = "$dir/ewm-db".toPath()
        assertFailsWith<IllegalStateException> {
            installSeedBytesAtomically(
                dbPath = dbPath,
                seedBytes = byteArrayOf(9, 9, 9),
                beforeMove = { throw IllegalStateException("fail before move") },
            )
        }
        assertFalse(fs.exists(dbPath), "failed seed must not leave public ewm-db")
        val tmp = "$dir/ewm-db.seed.tmp".toPath()
        assertFalse(fs.exists(tmp), "temp must be cleaned")
        runCatching { fs.delete(dir.toPath()) }
    }
}
