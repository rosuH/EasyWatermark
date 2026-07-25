package me.rosuh.easywatermark.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * J3 — pure path policy + safe legacy migration (no live home mutation).
 */
class DesktopAppPathsTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun macDataRoot_applicationSupport() {
        val dir = DesktopAppPaths.osNativeDataDir(osName = "Mac OS X", home = "/Users/me")
        assertEquals(
            File("/Users/me/Library/Application Support/EasyWatermark"),
            dir,
        )
    }

    @Test
    fun windowsDataRoot_localAppData() {
        val local = "C:/Users/me/AppData/Local"
        val dir = DesktopAppPaths.osNativeDataDir(
            osName = "Windows 11",
            home = "C:/Users/me",
            env = { if (it == "LOCALAPPDATA") local else null },
        )
        // File normalizes separators per host OS; compare path string loosely.
        assertTrue(dir.path.replace('\\', '/').endsWith("EasyWatermark"))
        assertTrue(dir.path.replace('\\', '/').contains("AppData/Local") || dir.path.contains("AppData"))
        assertEquals(File(local, DesktopAppPaths.APP_DISPLAY_NAME).path, dir.path)
    }

    @Test
    fun linuxDataRoot_xdgThenDefault() {
        val xdg = DesktopAppPaths.osNativeDataDir(
            osName = "Linux",
            home = "/home/me",
            env = { if (it == "XDG_DATA_HOME") "/home/me/.local/share-custom" else null },
        )
        assertEquals(File("/home/me/.local/share-custom/easywatermark"), xdg)
        val def = DesktopAppPaths.osNativeDataDir(
            osName = "Linux",
            home = "/home/me",
            env = { null },
        )
        assertEquals(File("/home/me/.local/share/easywatermark"), def)
    }

    @Test
    fun macCacheRoot() {
        val dir = DesktopAppPaths.osNativeCacheDir(osName = "Mac OS X", home = "/Users/me")
        assertEquals(File("/Users/me/Library/Caches/EasyWatermark"), dir)
    }

    @Test
    fun migrateLegacy_copyForwardNoDelete() {
        val tmp = tempDir("j3-paths-")
        try {
            val legacy = File(tmp, ".easywatermark").apply { mkdirs() }
            File(legacy, "note.txt").writeText("keep-me")
            File(legacy, "datastore").mkdirs()
            File(legacy, "datastore/a.preferences_pb").writeText("prefs")
            val native = File(tmp, "Application Support/EasyWatermark")

            assertTrue(DesktopAppPaths.migrateLegacyIfNeeded(legacy, native))
            assertTrue(File(native, "note.txt").isFile)
            assertEquals("keep-me", File(native, "note.txt").readText())
            assertTrue(File(native, "datastore/a.preferences_pb").isFile)
            // Legacy preserved
            assertTrue(File(legacy, "note.txt").isFile)
            // Second run: native has content → no re-migration flag required; still no overwrite
            File(native, "note.txt").writeText("user-edited")
            assertFalse(DesktopAppPaths.migrateLegacyIfNeeded(legacy, native))
            assertEquals("user-edited", File(native, "note.txt").readText())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun migrateLegacy_skipsWhenLegacyEmpty() {
        val tmp = tempDir("j3-empty-")
        try {
            val legacy = File(tmp, ".easywatermark").apply { mkdirs() }
            val native = File(tmp, "native")
            assertFalse(DesktopAppPaths.migrateLegacyIfNeeded(legacy, native))
            assertFalse(native.exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun resolveAppDataDir_fallbackWithoutHome() {
        val fb = File("build/j3-fallback-test").apply { deleteRecursively() }
        val dir = DesktopAppPaths.resolveAppDataDir(home = null, fallbackWhenNoHome = fb)
        assertEquals(fb.canonicalFile, dir.canonicalFile)
        assertTrue(dir.isDirectory)
        fb.deleteRecursively()
    }
}
