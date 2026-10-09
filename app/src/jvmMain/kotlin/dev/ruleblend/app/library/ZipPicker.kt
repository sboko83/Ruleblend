package dev.ruleblend.app.library

import dev.ruleblend.app.util.DesktopEnvironment
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Where to save an exported library; `null` when the user cancels. `.zip` is appended if missing. */
fun pickZipToSave(defaultName: String = "ruleblend-library.zip"): Path? =
    pickZip(FileDialog.SAVE, "Export library", defaultName)?.let {
        if (it.fileName.toString().endsWith(".zip")) it else Path.of("$it.zip")
    }

/** An archive to import; `null` when the user cancels. */
fun pickZipToOpen(): Path? = pickZip(FileDialog.LOAD, "Import library", null)

/** macOS gets the native dialog; other platforms get [JFileChooser]. */
private fun pickZip(mode: Int, title: String, defaultName: String?): Path? =
    DesktopEnvironment.actions.archive(mode, title, defaultName)

internal fun nativePickZip(mode: Int, title: String, defaultName: String?): Path? {
    if (!System.getProperty("os.name").startsWith("Mac")) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileFilter = FileNameExtensionFilter("Zip archive", "zip")
            defaultName?.let { selectedFile = java.io.File(it) }
        }
        val result = if (mode == FileDialog.SAVE) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }
    val dialog = FileDialog(null as Frame?, title, mode).apply {
        defaultName?.let { file = it }
        setFilenameFilter { _, name -> name.endsWith(".zip") }
        isVisible = true
    }
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return Path.of(directory, file)
}
