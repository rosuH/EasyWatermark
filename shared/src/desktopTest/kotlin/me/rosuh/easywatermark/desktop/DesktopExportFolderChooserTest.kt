package me.rosuh.easywatermark.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DesktopExportFolderChooserTest {

    @Test
    fun mac_cancel_does_not_treat_leftover_directory_as_choice() {
        val dir = File(System.getProperty("java.io.tmpdir")!!).absoluteFile
        assertTrue(dir.isDirectory)
        assertNull(DesktopExportFolderChooser.resolveMacChosenDirectory(dir.path, null))
        assertNull(DesktopExportFolderChooser.resolveMacChosenDirectory(dir.path, ""))
        assertNull(DesktopExportFolderChooser.resolveMacChosenDirectory(null, dir.name))
    }

    @Test
    fun mac_success_joins_parent_and_folder_name() {
        val parent = File(System.getProperty("java.io.tmpdir")!!).absoluteFile
        val child = File(parent, "ewm-export-folder-chooser-test").apply {
            mkdirs()
            deleteOnExit()
        }
        assertEquals(
            child.canonicalFile,
            DesktopExportFolderChooser.resolveMacChosenDirectory(
                parent.path,
                child.name,
            )?.canonicalFile,
        )
    }

    @Test
    fun mac_directory_property_is_restored_after_dialog_and_on_throw() {
        val key = DesktopExportFolderChooser.MAC_DIRECTORY_DIALOG_PROPERTY
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)
            val picked = File(System.getProperty("java.io.tmpdir")!!)
            val result = DesktopExportFolderChooser.withMacDirectoryDialogProperty {
                assertEquals("true", System.getProperty(key))
                picked
            }
            assertEquals(picked, result)
            assertNull(System.getProperty(key))

            System.setProperty(key, "false")
            try {
                DesktopExportFolderChooser.withMacDirectoryDialogProperty {
                    assertEquals("true", System.getProperty(key))
                    error("dialog failed")
                }
                fail("expected throw")
            } catch (e: IllegalStateException) {
                assertEquals("dialog failed", e.message)
            }
            assertEquals("false", System.getProperty(key))
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun desktop_window_uses_native_export_folder_chooser() {
        val window = readFirst(
            "desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt",
        )
        assertTrue(
            "DesktopExportFolderChooser.choose" in window,
            "export folder must go through DesktopExportFolderChooser",
        )
        assertTrue(
            "JFileChooser" !in window,
            "DesktopWindow must not open Swing JFileChooser for export folder",
        )
        val chooser = readFirst(
            "shared/src/desktopMain/kotlin/me/rosuh/easywatermark/desktop/DesktopExportFolderChooser.kt",
        )
        assertTrue(
            "apple.awt.fileDialogForDirectories" in chooser &&
                "FileDialog" in chooser,
            "macOS export folder must use native FileDialog directory mode",
        )
    }

    private fun readFirst(vararg paths: String): String {
        val cwd = File("").absoluteFile
        val candidates = paths.flatMap { path ->
            listOf(File(path), File(cwd, path), File(cwd.parentFile, path))
        }
        return candidates.first { it.isFile }.readText()
    }
}
