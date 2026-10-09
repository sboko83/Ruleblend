package dev.ruleblend.app.util

import java.nio.file.Path
import dev.ruleblend.app.library.nativePickZip

/** OS boundaries shared by UI handlers and their background work. One binding per process. */
internal interface DesktopActions {
    fun directory(title: String, initialDirectory: Path?): Path?
    fun archive(mode: Int, title: String, defaultName: String?): Path?
    fun editor(title: String): Path?
    fun reveal(path: Path)
    fun openUrl(url: String)
    fun edit(path: Path, editor: Path?)
    fun probe(executable: String): CliProbe?
    fun launch(request: CliLaunch)
}

internal object NativeDesktopActions : DesktopActions {
    override fun directory(title: String, initialDirectory: Path?) = nativePickDirectory(title, initialDirectory)
    override fun archive(mode: Int, title: String, defaultName: String?) = nativePickZip(mode, title, defaultName)
    override fun editor(title: String) = nativePickExternalEditor(title)
    override fun reveal(path: Path) = nativeRevealInFileManager(path)
    override fun openUrl(url: String) = nativeOpenUrl(url)
    override fun edit(path: Path, editor: Path?) = nativeOpenInExternalEditor(path, editor)
    override fun probe(executable: String) = nativeProbeCli(executable)
    override fun launch(request: CliLaunch) = nativeLaunchAgentCli(request)
}

/** Bind before creating the root; changing a live process is unsupported. E2E restarts a JVM. */
internal object DesktopEnvironment {
    var actions: DesktopActions = NativeDesktopActions
}
