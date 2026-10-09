package dev.ruleblend.app.util

import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.io.path.exists

/**
 * Folder picker; `null` when the user cancels. macOS gets the native Finder dialog
 * (Swing's own chooser has no way to type a path there); other platforms get [JFileChooser].
 * [initialDirectory], if given and existing, is pre-selected when the dialog opens.
 */
fun pickDirectory(title: String, initialDirectory: Path? = null): Path? =
    DesktopEnvironment.actions.directory(title, initialDirectory)

internal fun nativePickDirectory(title: String, initialDirectory: Path?): Path? {
    if (!System.getProperty("os.name").startsWith("Mac")) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
            if (initialDirectory != null && initialDirectory.exists()) currentDirectory = initialDirectory.toFile()
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (initialDirectory != null && initialDirectory.exists()) dialog.directory = initialDirectory.toString()
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return Path.of(directory, file)
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}
