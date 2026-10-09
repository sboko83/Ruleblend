package dev.ruleblend.core.process

import java.nio.file.Path
import java.util.Base64

/** Unicode-safe CreateProcessW bridge, independent of Java 17's native ANSI code page. */
object WindowsCommand {
    fun powershell(script: String, executable: Path = WindowsExecutables().systemFile("WindowsPowerShell/v1.0/powershell.exe")): List<String> =
        listOf(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))

    fun script(command: List<String>, directory: Path? = null, newWindow: Boolean = false, wait: Boolean = true): String {
        require(command.isNotEmpty() && command.first().isNotBlank()) { "An executable is required" }
        require(command.all { '\u0000' !in it }) { "Process arguments cannot contain NUL" }
        val batch = command.first().endsWith(".cmd", true) || command.first().endsWith(".bat", true)
        val executable: String
        val arguments: String
        if (batch) {
            require(command.none { '\r' in it || '\n' in it }) { "Batch arguments cannot contain line breaks" }
            executable = WindowsExecutables().systemFile("cmd.exe").toString()
            // The batch wrapper forwards %*: both the initial shell and that forwarding parse
            // must consume an escape layer, including quotes, percent signs and exclamation marks.
            val invocation = escapeCmd(command.first()) + command.drop(1)
                .joinToString("") { " " + escapeCmd(escapeCmd(quote(it))) }
            arguments = "/d /v:off /s /c \"$invocation\""
        } else {
            executable = command.first()
            arguments = command.drop(1).joinToString(" ", transform = ::quote)
        }
        return startScript(executable, arguments, directory, newWindow, wait)
    }

    /** cmd only sees a fixed PowerShell invocation and base64, never user paths or arguments. */
    fun commandPrompt(script: String, executable: Path, directory: Path? = null,
        newWindow: Boolean = false, wait: Boolean = true): String {
        val invocation = "\"%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe\" " +
            powershell(script).drop(1).joinToString(" ")
        return startScript(executable.toString(), "/d /v:off /s /c \"$invocation\"", directory, newWindow, wait)
    }

    private fun startScript(executable: String, arguments: String, directory: Path?, newWindow: Boolean, wait: Boolean): String =
        buildString {
            appendLine("\$ErrorActionPreference = 'Stop'")
            appendLine("\$p = New-Object System.Diagnostics.ProcessStartInfo")
            appendLine("\$p.FileName = ${literal(executable)}")
            appendLine("\$p.Arguments = ${literal(arguments)}")
            directory?.let { appendLine("\$p.WorkingDirectory = ${literal(it.toAbsolutePath().toString())}") }
            appendLine("\$p.UseShellExecute = \$${newWindow.toString().lowercase()}")
            appendLine("\$child = [System.Diagnostics.Process]::Start(\$p)")
            if (wait) appendLine("\$child.WaitForExit(); exit \$child.ExitCode")
        }

    /** CommandLineToArgvW / CRT quoting; no command shell is involved for native executables. */
    fun quote(value: String): String = buildString {
        append('"')
        var slashes = 0
        for (character in value) {
            if (character == '\\') { slashes++; continue }
            repeat(if (character == '"') slashes * 2 + 1 else slashes) { append('\\') }
            append(character)
            slashes = 0
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }

    private fun escapeCmd(value: String): String = buildString {
        value.forEach { character ->
            if (character in "()[]%!^\"`<>&|;, *?") append('^')
            append(character)
        }
    }

    private fun literal(value: String): String = "'" + value.replace("'", "''") + "'"
}
