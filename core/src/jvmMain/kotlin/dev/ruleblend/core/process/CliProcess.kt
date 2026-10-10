package dev.ruleblend.core.process

import dev.ruleblend.core.integration.ModRuntimeError
import dev.ruleblend.core.integration.ModRuntimeException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class CliProcessResult(val exitCode: Int, val stdout: String, val stderr: String = "")

/** Injectable argv boundary. Implementations never print child output to MCP stdout. */
fun interface CliProcess {
    fun run(arguments: List<String>): CliProcessResult
}

/** Drains both pipes concurrently; the cap also applies to a noisy stderr stream. */
class BoundedCliProcess(
    private val command: List<String>,
    private val directory: Path? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val replaceEnvironment: Boolean = false,
    private val timeoutMs: Long = 25_000,
    private val maxOutputBytes: Int = 1024 * 1024,
    private val cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
) : CliProcess {
    override fun run(arguments: List<String>): CliProcessResult {
        if (cancelled()) fail(ModRuntimeError.CANCELLED)
        val argv = command + arguments
        require(argv.isNotEmpty() && argv.all { '\u0000' !in it })
        val native = if (System.getProperty("os.name").startsWith("Windows"))
            WindowsCommand.powershell(WindowsCommand.script(argv, directory)) else argv
        val process = try {
            ProcessBuilder(native).apply {
                directory?.let { directory(it.toFile()) }
                if (replaceEnvironment) environment().clear()
                environment().putAll(environment)
            }.start()
        } catch (_: Exception) { fail(ModRuntimeError.CLI_UNAVAILABLE) }
        process.outputStream.close()
        val overflow = AtomicBoolean(false)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        fun drain(input: java.io.InputStream, output: ByteArrayOutputStream) = thread(isDaemon = true) {
            runCatching {
                input.use {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        val retain = minOf(count, maxOf(0, maxOutputBytes - output.size()))
                        if (retain > 0) output.write(buffer, 0, retain)
                        if (retain != count) overflow.set(true)
                    }
                }
            }
        }
        val readers = listOf(drain(process.inputStream, out), drain(process.errorStream, err))
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                if (cancelled()) fail(ModRuntimeError.CANCELLED)
                if (overflow.get()) fail(ModRuntimeError.OUTPUT_LIMIT)
                if (System.nanoTime() >= deadline) fail(ModRuntimeError.TIMEOUT)
                if (process.waitFor(20, TimeUnit.MILLISECONDS)) break
            }
            readers.forEach { it.join(minOf(timeoutMs, 1000)) }
            if (readers.any { it.isAlive }) fail(ModRuntimeError.TIMEOUT)
            if (overflow.get()) fail(ModRuntimeError.OUTPUT_LIMIT)
            return CliProcessResult(process.exitValue(), out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(ModRuntimeError.CANCELLED)
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    private fun fail(error: ModRuntimeError): Nothing = throw ModRuntimeException(error, "CLI process: $error")
}

data class CliProbe(val path: String, val interactive: Boolean, val searchPath: String? = null)

/** Shared lookup for desktop launching and noninteractive adapters, including GUI macOS PATH. */
fun resolveCli(executable: String): CliProbe? {
    if (System.getProperty("os.name").startsWith("Windows"))
        return WindowsExecutables().find(executable)?.let { CliProbe(it.toString(), false) }
    val shell = System.getenv("SHELL")?.takeIf(String::isNotBlank) ?: "/bin/sh"
    for (interactive in listOf(false, true)) {
        val result = runCatching {
            BoundedCliProcess(listOf(shell, if (interactive) "-ilc" else "-lc"), timeoutMs = 8000)
                .run(listOf("command -v \"${'$'}1\" || true; printf '\\nRULEBLEND_CLI_PATH=%s\\n' \"${'$'}PATH\"", "sh", executable))
        }.getOrNull() ?: continue
        result.stdout.lineSequence().map(String::trim)
            .lastOrNull { it.startsWith("/") && Files.isRegularFile(Path.of(it)) }
            ?.let { path -> return CliProbe(path, interactive, result.stdout.lineSequence()
                .lastOrNull { it.startsWith("RULEBLEND_CLI_PATH=") }?.substringAfter('=')) }
    }
    return null
}

/** Launch directly with the captured login PATH so shell greetings cannot corrupt CLI JSON. */
fun resolvedCliProcess(executable: String, directory: Path? = null): CliProcess {
    val runner by lazy {
        val probe = resolveCli(executable) ?: throw ModRuntimeException(ModRuntimeError.CLI_UNAVAILABLE, "CLI executable is unavailable")
        BoundedCliProcess(listOf(probe.path), directory = directory,
            environment = probe.searchPath?.let { mapOf("PATH" to it) }.orEmpty())
    }
    return CliProcess { runner.run(it) }
}
