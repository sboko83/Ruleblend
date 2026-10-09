package dev.ruleblend.app.util

import dev.ruleblend.core.process.WindowsCommand
import dev.ruleblend.core.process.WindowsExecutables
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun windowsTerminalCommand(terminal: Path, payload: List<String>): List<String> =
    when (terminal.fileName.toString().lowercase()) {
        "wt.exe", "windowsterminal.exe" -> listOf(terminal.toString(), "-w", "new", "new-tab") + payload
        "powershell.exe", "pwsh.exe" -> listOf(terminal.toString()) + payload.drop(1)
        else -> error("Choose Windows Terminal (wt.exe), PowerShell or cmd.exe as the terminal")
    }

internal fun launchWindowsAgent(
    launch: CliLaunch,
    executables: WindowsExecutables = WindowsExecutables(),
    start: (List<String>) -> Unit = ::startWindowsDesktopProcess,
) {
    require(Files.isDirectory(launch.directory)) { "Project folder no longer exists: ${launch.directory}" }
    require(launch.command.isNotEmpty()) { "An assistant command is required" }
    val executable = executables.find(launch.command.first())
        ?: error("Assistant executable was not found: ${launch.command.first()}")
    val agentScript = WindowsCommand.script(
        listOf(executable.toString()) + launch.command.drop(1), launch.directory,
    )
    val payload = WindowsCommand.powershell(agentScript)
    val selected = launch.terminalApp
    if (selected != null) {
        require(Files.isRegularFile(selected)) { "Terminal application does not exist: $selected" }
        val script = if (selected.fileName.toString().equals("cmd.exe", true)) {
            WindowsCommand.commandPrompt(agentScript, selected, newWindow = true, wait = false)
        } else {
            WindowsCommand.script(windowsTerminalCommand(selected, payload), newWindow = true, wait = false)
        }
        start(WindowsCommand.powershell(script))
        return
    }
    val terminal = executables.find("wt.exe")
    if (terminal != null) {
        val result = runCatching {
            start(WindowsCommand.powershell(WindowsCommand.script(windowsTerminalCommand(terminal, payload))))
        }
        if (result.isSuccess) return
    }
    // ShellExecute opens a console even when the parent was launched from the Start menu.
    start(WindowsCommand.powershell(WindowsCommand.script(payload, newWindow = true, wait = false)))
}

internal fun startWindowsDesktopProcess(command: List<String>) {
    val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.close()
    try {
        check(process.waitFor(15, TimeUnit.SECONDS)) { "Opening the external application timed out" }
        check(process.exitValue() == 0) { "Windows could not open the external application" }
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
