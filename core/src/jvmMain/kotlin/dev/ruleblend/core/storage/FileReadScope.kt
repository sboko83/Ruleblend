package dev.ruleblend.core.storage

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Reads every file at most once for the span of one scan.
 *
 * A place is described by a handful of files, but a screenful of answers about it — is this block
 * installed, is that one drifted, what does the run look like, does the file hold hand-written text
 * — each used to open those files again. With a hundred library objects that is a hundred reads of
 * the same `AGENTS.md` per refresh.
 *
 * The scope is read-only and short: it is opened around a scan and closed with it. Nothing that
 * writes may run inside one, because the cache would then describe a file that has since changed on
 * disk. Callers that write open no scope, so the ordinary path is unaffected.
 *
 * State is per thread: a scan runs on one thread, and two scans on two threads cache separately.
 */
object FileReadScope {

    private class Scope {
        val texts = HashMap<Path, String?>()
        val values = HashMap<Any, Any>()
        var reads: Int = 0
    }

    private val active = ThreadLocal<Scope?>()

    /** Runs [block] with one shared read of every file it touches. Scopes nest without re-reading. */
    fun <T> reading(block: () -> T): T {
        if (active.get() != null) return block()
        active.set(Scope())
        return try {
            block()
        } finally {
            active.remove()
        }
    }

    /** The file's text, or `null` when it does not exist. Read once per scope. */
    fun textOrNull(path: Path): String? {
        val scope = active.get() ?: return readFile(path)
        // An absent file is an answer too, and `null` must not read as "not looked at yet".
        if (path in scope.texts) return scope.texts[path]
        scope.reads++
        return readFile(path).also { scope.texts[path] = it }
    }

    /** The file's text, or an empty string when it does not exist. */
    fun text(path: Path): String = textOrNull(path).orEmpty()

    /** Whether the file exists, answered from the scope's read rather than from a second stat. */
    fun exists(path: Path): Boolean = textOrNull(path) != null

    /**
     * Memoizes [compute] under [key] for the span of the scope, for answers that are parsed rather
     * than read — a sidecar JSON deserialized once per scan instead of once per object asked about.
     * Outside a scope it computes every time, like the reads do.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> cached(key: Any, compute: () -> T): T {
        val scope = active.get() ?: return compute()
        return scope.values.getOrPut(key) { compute() } as T
    }

    /** How many files were actually opened inside the active scope; `0` outside one. */
    val reads: Int get() = active.get()?.reads ?: 0

    private fun readFile(path: Path): String? =
        if (path.exists()) runCatching { path.readText() }.getOrNull() else null
}
