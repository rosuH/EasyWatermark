package me.rosuh.easywatermark.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * Export-folder pick at the Desktop edge.
 *
 * macOS: native AWT [FileDialog] in directory mode (`apple.awt.fileDialogForDirectories`).
 * Other OS: Swing [JFileChooser] — AWT has no folder mode there.
 * The macOS property is restored after the dialog so Open image stays a file picker.
 */
object DesktopExportFolderChooser {

    internal const val MAC_DIRECTORY_DIALOG_PROPERTY = "apple.awt.fileDialogForDirectories"

    fun choose(owner: Frame, title: String, current: File): File? =
        if (isMacOsName()) {
            chooseWithMacFileDialog(owner, title, current)
        } else {
            chooseWithSwing(owner, title, current)
        }

    /**
     * macOS directory [FileDialog]: cancel leaves [fileName] null — do not treat the
     * leftover [directory] as a choice.
     */
    internal fun resolveMacChosenDirectory(directory: String?, fileName: String?): File? {
        if (fileName.isNullOrBlank() || directory.isNullOrBlank()) return null
        return File(directory, fileName).takeIf { it.isDirectory }
    }

    internal fun withMacDirectoryDialogProperty(block: () -> File?): File? {
        val previous = System.getProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
        System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, "true")
        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(MAC_DIRECTORY_DIALOG_PROPERTY)
            } else {
                System.setProperty(MAC_DIRECTORY_DIALOG_PROPERTY, previous)
            }
        }
    }

    private fun chooseWithMacFileDialog(owner: Frame, title: String, current: File): File? =
        withMacDirectoryDialogProperty {
            val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
                isMultipleMode = false
                if (current.isDirectory) directory = current.absolutePath
                isVisible = true
            }
            resolveMacChosenDirectory(dialog.directory, dialog.file)
        }

    private fun chooseWithSwing(owner: Frame, title: String, current: File): File? {
        val chooser = JFileChooser(current).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.takeIf { it.isDirectory }
    }

    private fun isMacOsName(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")
}
