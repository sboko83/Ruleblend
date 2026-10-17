package dev.ruleblend.mcp

import dev.ruleblend.core.storage.AtomicWrite
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import java.util.jar.Manifest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Which kind of build the shared POSIX launcher re-launches. */
enum class McpServingBuild { INSTALLED, DEV }

/** What the shared launcher points at right now, as its last writer recorded it. */
data class McpServing(
    val build: McpServingBuild,
    /** The app binary of an installed build, the working directory of a dev run. */
    val source: String,
    /** True when the launcher re-launches exactly the build this process runs. */
    val thisBuild: Boolean,
)

/** What an agent has to launch to speak MCP with this build, and which kind of build it is. */
data class McpLaunch(
    /** Executable and arguments are passed directly, without a command shell. */
    val command: Path,
    /** True when this process is an unpackaged dev run rather than an installed app. */
    val fromDevBuild: Boolean,
    /** Who the shared launcher serves; `null` where agents get this build's binary directly (Windows). */
    val serving: McpServing? = null,
) {
    val args: List<String> get() = listOf("--mcp")

    /** A manual command for the platform's usual shell; registrations never go through a shell. */
    fun shellCommand(windows: Boolean = System.getProperty("os.name").startsWith("Windows")): String =
        if (windows) "& '${command.toString().replace("'", "''")}' --mcp"
        else "'${command.toString().replace("'", "'\\''")}' --mcp"
}

/**
 * Resolves the launch command for both ways Ruleblend runs.
 *
 * macOS and Linux: agents always get one script, [scriptPath], whatever build is running. The script
 * re-launches either the installed app (jpackage sets `jpackage.app-path` to the launcher inside the
 * bundle) or a dev run (IDE or `gradlew run`: this JVM plus a classpath of build outputs). Switching
 * between them rewrites the script and never an agent config, so no agent entry goes stale.
 *
 * The script records its owner. On start a build takes it over only when it is missing, unmarked,
 * already owned by the same kind of build, or points at files that no longer exist — so a dev run
 * that owns it picks up every rebuild, and an installed app started meanwhile does not take it back.
 * [claim] switches the owner explicitly.
 *
 * Windows: the installed app's fixed install path goes to the agents unchanged; a dev run gets a
 * GUI launcher re-launching this JVM and classpath, rewritten on every start at a stable path.
 */
class McpLauncher(
    private val scriptPath: Path,
    /** Class holding `main`; re-launched by the dev script. */
    private val mainClass: String,
    private val appPath: String? = System.getProperty("jpackage.app-path"),
    private val javaHome: String = System.getProperty("java.home"),
    private val classPath: String = System.getProperty("java.class.path"),
    private val workingDir: String = System.getProperty("user.dir"),
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {
    private val build = if (appPath != null) McpServingBuild.INSTALLED else McpServingBuild.DEV

    /** The start-time resolution: takes the shared launcher over only where that cannot surprise. */
    fun resolve(): McpLaunch {
        if (windows) return appPath?.let { McpLaunch(Path.of(it), fromDevBuild = false) } ?: windowsLaunch()
        val current = readScript()
        val claimable = current == null || current.owner == null || current.owner == build ||
            current.requires.any { !Files.exists(Path.of(it)) }
        return if (claimable) claim() else launch(current)
    }

    /** Points the shared launcher at this build, whoever owned it. */
    fun claim(): McpLaunch {
        if (windows) return resolve()
        AtomicWrite.write(scriptPath, script())
        Files.setPosixFilePermissions(
            scriptPath,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        )
        return launch(readScript())
    }

    private fun launch(script: LauncherScript?): McpLaunch = McpLaunch(
        scriptPath,
        fromDevBuild = build == McpServingBuild.DEV,
        serving = script?.owner?.let { McpServing(it, script.source, thisBuild = script.text == script()) },
    )

    /** Owner, source and required paths from the marker lines; `null` when there is no script yet. */
    private fun readScript(): LauncherScript? {
        if (!Files.exists(scriptPath)) return null
        val text = Files.readString(scriptPath)
        fun values(key: String) = text.lineSequence().filter { it.startsWith("$MARKER$key: ") }
            .map { it.removePrefix("$MARKER$key: ") }.toList()
        val owner = values("owner").singleOrNull()?.let { name -> McpServingBuild.entries.find { it.marker == name } }
        return LauncherScript(text, owner, values("source").singleOrNull().orEmpty(), values("requires"))
    }

    private class LauncherScript(val text: String, val owner: McpServingBuild?, val source: String, val requires: List<String>)

    private fun windowsLaunch(): McpLaunch {
        val binary = scriptPath.resolveSibling("${scriptPath.fileName}.exe").toAbsolutePath()
        // A manifest keeps the command below Windows' length limit. URI encoding preserves Unicode,
        // spaces and shell characters without depending on the JDK's argument-file code page.
        val appDir = binary.parent.resolve("app")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = classPath.split(File.pathSeparator).flatMap { entry ->
                if (entry.endsWith("*")) {
                    Files.list(Path.of(entry.dropLast(1))).use { files ->
                        files.filter { it.fileName.toString().endsWith(".jar", ignoreCase = true) }.toList()
                    }
                } else listOf(Path.of(entry.ifEmpty { "." }))
            }.joinToString(" ") { it.toAbsolutePath().toUri().toASCIIString() }
        }
        val bytes = ByteArrayOutputStream().apply {
            JarOutputStream(this).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF").apply { time = 0 })
                manifest.write(jar)
                jar.closeEntry()
            }
        }.toByteArray()
        // A running JVM locks its classpath JAR. New classpaths get a new file; existing sessions
        // retain theirs while the next process reads the atomically updated configuration.
        val classpathJar = appDir.resolve("${scriptPath.fileName}-${digest(bytes).take(16)}.jar")
        if (!Files.exists(classpathJar)) AtomicWrite.write(classpathJar, bytes)
        require(listOf(javaHome, mainClass).none { '\n' in it || '\r' in it }) { "Invalid MCP launcher configuration" }
        AtomicWrite.write(appDir.resolve("${scriptPath.fileName}.cfg"), """
            [Application]
            app.runtime=$javaHome
            app.classpath=${'$'}APPDIR\${classpathJar.fileName}
            app.mainclass=$mainClass
            [JavaOptions]
            java-options=-Dfile.encoding=UTF-8
        """.trimIndent() + "\n")
        prepareWindowsBinary(binary)
        return McpLaunch(binary, fromDevBuild = true)
    }

    private fun prepareWindowsBinary(binary: Path) {
        val template = ZipFile(Path.of(javaHome).resolve("jmods/jdk.jpackage.jmod").toFile()).use { archive ->
            val entry = archive.getEntry("classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe")
                ?: error("This JDK has no Windows MCP launcher")
            archive.getInputStream(entry).use { it.readBytes() }
        }
        val patcher = McpLauncher::class.java.getResourceAsStream("EnableUtf8Launcher.ps1")
            ?.use { it.readBytes() } ?: error("Windows MCP launcher helper is missing")
        val inputs = digest(template + patcher)
        val stamp = binary.resolveSibling("${binary.fileName}.sha256")
        if (Files.exists(binary) && Files.exists(stamp) &&
            Files.readString(stamp) == "$inputs:${digest(Files.readAllBytes(binary))}") return
        val temp = Files.createTempFile(binary.parent, "mcp-launcher-", ".exe")
        val script = Files.createTempFile(binary.parent, "mcp-launcher-", ".ps1")
        val log = Files.createTempFile(binary.parent, "mcp-launcher-", ".log")
        try {
            Files.write(temp, template)
            Files.write(script, patcher)
            val powershell = Path.of(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
            val process = ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-Path", temp.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Windows MCP launcher preparation timed out" }
                check(process.exitValue() == 0) { "Windows MCP launcher preparation failed: ${Files.readString(log)}" }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
            val prepared = Files.readAllBytes(temp)
            // Running Windows executables are locked. An unchanged launcher needs no replacement.
            if (!Files.exists(binary) || !Files.readAllBytes(binary).contentEquals(prepared)) AtomicWrite.write(binary, prepared)
            AtomicWrite.write(stamp, "$inputs:${digest(prepared)}")
        } finally {
            Files.deleteIfExists(temp)
            Files.deleteIfExists(script)
            Files.deleteIfExists(log)
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun script(): String {
        val (source, requires, exec) = when (val app = appPath) {
            null -> {
                val java = Path.of(javaHome).resolve("bin").resolve("java").toString()
                // Only entries present now: a classpath may name directories a module never fills,
                // and those must not read as a vanished build later.
                val entries = classPath.split(File.pathSeparator).filter { it.isNotEmpty() }
                    .map { it.removeSuffix("*") }.filter { Files.exists(Path.of(it)) }
                Triple(workingDir, listOf(java) + entries, "exec ${shellQuote(java)} -cp ${shellQuote(classPath)} $mainClass")
            }
            else -> Triple(app, listOf(app), "exec ${shellQuote(app)}")
        }
        require((requires + source + mainClass).none { '\n' in it || '\r' in it }) { "Invalid MCP launcher configuration" }
        val args = "\"${'$'}@\""
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("# Generated by Ruleblend — do not edit. Settings → Agents picks the build it launches;")
            appendLine("# $args carries --mcp through.")
            appendLine("${MARKER}owner: ${build.marker}")
            appendLine("${MARKER}source: $source")
            requires.forEach { appendLine("${MARKER}requires: $it") }
            appendLine("$exec $args")
        }
    }

    /** Single-quoted, so a path with spaces or `$` survives; an embedded quote is closed and re-opened. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val MARKER = "# ruleblend-mcp-"
        val McpServingBuild.marker get() = name.lowercase()
    }
}
