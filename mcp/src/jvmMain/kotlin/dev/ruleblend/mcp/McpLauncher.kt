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

/** What an agent has to launch to speak MCP with this build, and which kind of build it is. */
data class McpLaunch(
    /** Executable and arguments are passed directly, without a command shell. */
    val command: Path,
    /** True when [command] is the generated dev wrapper instead of an installed app binary. */
    val fromDevBuild: Boolean,
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
 * Installed app: jpackage sets `jpackage.app-path` to the launcher inside the bundle, and that path
 * goes to the agents unchanged.
 *
 * Dev run (IDE or `gradlew run`): there is no single binary — the app is this JVM plus a classpath
 * of build outputs. A POSIX script or a Windows GUI launcher re-launching this JVM and classpath is
 * written next to the library, and the agents get that path. Connecting therefore works long before
 * a packaged app exists, and points at the build the app was started from.
 *
 * Its configuration is rewritten on every app start, while its path stays the same: a rebuild is picked up
 * without touching any agent config, and no agent entry goes stale.
 */
class McpLauncher(
    private val scriptPath: Path,
    /** Class holding `main`; re-launched by the dev script. */
    private val mainClass: String,
    private val appPath: String? = System.getProperty("jpackage.app-path"),
    private val javaHome: String = System.getProperty("java.home"),
    private val classPath: String = System.getProperty("java.class.path"),
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
) {

    fun resolve(): McpLaunch {
        appPath?.let { return McpLaunch(Path.of(it), fromDevBuild = false) }
        if (windows) return windowsLaunch()
        AtomicWrite.write(scriptPath, script())
        Files.setPosixFilePermissions(
            scriptPath,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
        )
        return McpLaunch(scriptPath, fromDevBuild = true)
    }

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
        val java = Path.of(javaHome).resolve("bin").resolve("java").toString()
        val args = "\"${'$'}@\""
        return """
            #!/bin/sh
            # Generated by Ruleblend on every app start — do not edit.
            # Re-launches the dev build the app was started from; $args carries --mcp through.
            exec ${shellQuote(java)} -cp ${shellQuote(classPath)} $mainClass $args
        """.trimIndent() + "\n"
    }

    /** Single-quoted, so a path with spaces or `$` survives; an embedded quote is closed and re-opened. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
