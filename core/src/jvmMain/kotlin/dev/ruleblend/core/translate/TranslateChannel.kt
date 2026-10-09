package dev.ruleblend.core.translate

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * One JSON line out, one JSON line back.
 *
 * The wire format is the whole contract between Kotlin and the Swift helper, and it is worth
 * testing without the macOS Translation framework behind it: [AppleTranslator] speaks through this
 * channel, so a test can answer requests itself while the real helper is exercised separately.
 */
interface TranslateChannel {

    /** Whether a peer can be reached at all; `false` makes every request fail as unavailable. */
    val available: Boolean

    /** Sends one request line and returns the response line. Throws when the peer is unusable. */
    fun send(line: String): String

    /** Drops the current peer; the next [send] starts a fresh one. */
    fun reset()
}

/**
 * Talks to the bundled `rb-translate` helper over its stdin/stdout.
 *
 * The process is long-lived on purpose: creating a `TranslationSession` is the expensive step, and
 * the helper caches one per language pair. Spawning per request would put that cost on every
 * paragraph — the same trap that made a CLI agent unusable here at ~7 seconds a call.
 */
class HelperProcessChannel(
    private val helper: Path? = TranslateHelper.locate(),
    private val log: (String) -> Unit = { System.err.println("rb-translate: $it") },
) : TranslateChannel {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    override val available: Boolean get() = binary() != null

    override fun send(line: String): String {
        val binary = binary() ?: error("helper not found")
        ensureStarted(binary)
        val out = writer ?: error("helper stdin unavailable")
        val input = reader ?: error("helper stdout unavailable")
        out.write(line)
        out.write("\n")
        out.flush()
        return input.readLine() ?: error("helper closed its output")
    }

    override fun reset() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.destroyForcibly()
        process = null
        writer = null
        reader = null
    }

    private fun binary(): Path? = helper?.takeIf { it.exists() && it.isExecutable() }

    private fun ensureStarted(binary: Path) {
        val running = process
        if (running != null && running.isAlive) return
        reset()
        val started = ProcessBuilder(binary.toString())
            .redirectErrorStream(false)
            .start()
        process = started
        writer = started.outputWriter(StandardCharsets.UTF_8)
        reader = started.inputReader(StandardCharsets.UTF_8)
        // stderr would fill its pipe and wedge the helper if nobody drained it.
        Thread {
            started.errorReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach(log) }
        }.apply { isDaemon = true; name = "rb-translate-stderr" }.start()
    }
}

/** Where the helper lives at runtime. */
object TranslateHelper {

    const val BINARY_NAME: String = "rb-translate"

    /**
     * Compose sets `compose.application.resources.dir` for both `run` and the packaged app, so one
     * lookup covers development and the shipped bundle. `RULEBLEND_TRANSLATE_HELPER` overrides it
     * for manual testing against a locally built binary.
     */
    fun locate(): Path? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return null
        System.getenv("RULEBLEND_TRANSLATE_HELPER")?.let { return Path.of(it).takeIf { path -> path.exists() } }
        val resources = System.getProperty("compose.application.resources.dir") ?: return null
        return Path.of(resources).resolve(BINARY_NAME).takeIf { it.exists() }
    }
}
