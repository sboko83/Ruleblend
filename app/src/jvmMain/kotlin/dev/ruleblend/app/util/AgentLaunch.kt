package dev.ruleblend.app.util

import dev.ruleblend.core.integration.AgentLauncherScript
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.process.WindowsExecutables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** Where the executable was found and which shell mode found it — the script must use the same one. */
data class CliProbe(val path: String, val interactive: Boolean)

/** Where generated launchers live: beside the rest of the disposable machine state. */
fun defaultLauncherDirectory(): Path =
    Path.of(System.getProperty("user.home")).resolve(".ruleblend").resolve("launchers")

/** The user's shell, which is where their agent CLIs actually live. */
private fun userShell(): String = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"

/**
 * Looks [executable] up the way the user's terminal would. A bundled macOS app inherits a bare PATH
 * from `launchd`, so `command -v` inside the JVM finds nothing that Homebrew, nvm or mise installed;
 * the login shell is asked instead, and — only if that comes up empty — an interactive one, for
 * setups that build their PATH in `.zshrc` rather than in `.zprofile`.
 */
fun probeCli(executable: String): CliProbe? = DesktopEnvironment.actions.probe(executable)

internal fun nativeProbeCli(executable: String): CliProbe? {
    if (System.getProperty("os.name").startsWith("Windows")) {
        return WindowsExecutables().find(executable)?.let { CliProbe(it.toString(), interactive = false) }
    }
    val shell = userShell()
    probe(shell, "-lc", executable)?.let { return CliProbe(it, interactive = false) }
    return probe(shell, "-ilc", executable)?.let { CliProbe(it, interactive = true) }
}

private fun probe(shell: String, flags: String, executable: String): String? = runCatching {
    val process = ProcessBuilder(shell, flags, "command -v ${'$'}1 || true", "sh", executable)
        .redirectErrorStream(false)
        .start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(ProbeTimeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    // An interactive shell prints its own greetings first; the path is the last line that is one.
    output.lineSequence()
        .map(String::trim)
        .lastOrNull { it.startsWith("/") && Path.of(it).exists() }
}.getOrNull()

private const val ProbeTimeoutSeconds = 8L

/** One launch: what to run, where, and in which terminal. */
data class CliLaunch(
    val launcherDirectory: Path,
    val agentId: String,
    val directory: Path,
    val command: List<String>,
    val interactive: Boolean,
    val terminalApp: Path?,
)

/**
 * Writes the launcher and hands it to the terminal. The script is rewritten on every launch, so a
 * changed argument line or a moved project takes effect at once, and `open` is what puts it in
 * front of the user: with a chosen terminal in that application, without one in whichever
 * application owns `.command` files.
 */
fun launchAgentCli(launch: CliLaunch) = DesktopEnvironment.actions.launch(launch)

internal fun nativeLaunchAgentCli(launch: CliLaunch) {
    if (System.getProperty("os.name").startsWith("Windows")) {
        launchWindowsAgent(launch)
        return
    }
    val (launcherDirectory, agentId, directory, command, interactive, terminalApp) = launch
    require(directory.exists()) { "Project folder no longer exists: $directory" }
    val script = launcherDirectory.resolve(AgentLauncherScript.fileName(agentId, directory))
    AtomicWrite.write(
        script,
        AgentLauncherScript.render(
            shell = userShell(),
            directory = directory,
            command = command,
            interactive = interactive,
        ),
    )
    runCatching {
        Files.setPosixFilePermissions(
            script,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        )
    }
    val open = buildList {
        add("/usr/bin/open")
        terminalApp?.let {
            require(it.exists()) { "Terminal application does not exist: $it" }
            add("-a")
            add(it.toString())
        }
        add(script.toString())
    }
    ProcessBuilder(open).start()
}
