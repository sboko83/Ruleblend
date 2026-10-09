package dev.ruleblend.app.util

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import dev.ruleblend.core.process.WindowsCommand
import kotlin.io.path.exists

/**
 * Reveals [path] in the OS file manager with it selected (macOS Finder, Windows Explorer); falls
 * back to opening its parent folder. `Desktop` is the same AWT surface the app already relies on
 * for its taskbar icon and folder picker; no process spawn.
 */
fun revealInFileManager(path: Path) = DesktopEnvironment.actions.reveal(path)

internal fun nativeRevealInFileManager(path: Path) {
    val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    val file = path.toFile()
    if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
        // macOS/Windows: opens the parent and selects the entry.
        if (runCatching { desktop.browseFileDirectory(file) }.isSuccess) return
    }
    if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) {
        desktop.open(if (file.isDirectory) file else file.parentFile ?: file)
    } else {
        error("The operating system does not support opening folders")
    }
}

/** Where macOS keeps the Translation Languages list, the only place a language pack can be added. */
const val TRANSLATION_SETTINGS_URL: String = "x-apple.systempreferences:com.apple.Localization-Settings.extension"

/**
 * Hands [url] to the operating system. Used for macOS preference panes (`x-apple.systempreferences:`),
 * which `Desktop.browse` refuses because the scheme is not http.
 */
fun openUrl(url: String) = DesktopEnvironment.actions.openUrl(url)

internal fun nativeOpenUrl(url: String) {
    if (System.getProperty("os.name").startsWith("Mac")) {
        ProcessBuilder("/usr/bin/open", url).start()
    } else {
        val desktop = Desktop.getDesktop()
        check(desktop.isSupported(Desktop.Action.BROWSE)) { "No browser is available" }
        desktop.browse(URI(url))
    }
}

/** Opens [path] in [editor], or in the editor associated with its file type when none was selected. */
fun openInExternalEditor(path: Path, editor: Path? = null) = DesktopEnvironment.actions.edit(path, editor)

internal fun nativeOpenInExternalEditor(path: Path, editor: Path?) {
    if (editor != null) {
        require(editor.exists()) { "External editor does not exist: $editor" }
        val command = if (System.getProperty("os.name").startsWith("Mac") && editor.toString().endsWith(".app")) {
            listOf("/usr/bin/open", "-a", editor.toString(), path.toString())
        } else {
            listOf(editor.toString(), path.toString())
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            startWindowsDesktopProcess(WindowsCommand.powershell(WindowsCommand.script(command, wait = false)))
        } else {
            ProcessBuilder(command).start()
        }
        return
    }
    check(Desktop.isDesktopSupported()) { "The operating system does not support opening external editors" }
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.EDIT) && runCatching { desktop.edit(path.toFile()) }.isSuccess) return
    check(desktop.isSupported(Desktop.Action.OPEN)) { "No external editor is available for this file type" }
    desktop.open(path.toFile())
}
