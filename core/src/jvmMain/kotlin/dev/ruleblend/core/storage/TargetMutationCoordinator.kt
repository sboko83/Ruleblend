package dev.ruleblend.core.storage

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * The single entry point for read-modify-write cycles over user files outside the library: agent
 * instruction targets, agent MCP configs and the sidecar state describing what was written there.
 *
 * [AtomicWrite] rules out a half-written file, not a lost update: two writers that read the same
 * text and each replace the whole file keep only the result of the last rename. Both writers may
 * live in one process — MCP tool handlers run concurrently on `Dispatchers.IO` — or in two, since
 * the GUI app and the MCP server are separate processes over the same files.
 *
 * Every such cycle runs inside [mutate], so all Ruleblend writers serialize on the shared
 * [InterProcessLock]. It is the same lock the library and the config use, and it is reentrant, so
 * a target write nested in a library mutation is safe.
 *
 * The lock is advisory and covers Ruleblend only. An external editor that rewrites the same file
 * between a read and its rename never takes it, so [rewrite] guards that cycle by comparing the
 * file against what it read, right before the rename.
 */
class TargetMutationCoordinator(private val lock: InterProcessLock? = null) {

    /**
     * Runs [body] as one mutation. One lock covers every mutation, so [files] does not narrow it;
     * it names what the cycle covers — a target file and the record derived from it are named
     * together, which is what holds the pair — and says what was left alone when the wait runs out.
     */
    fun <T> mutate(vararg files: Path, body: () -> T): T {
        if (lock == null) return body()
        return try {
            lock.withLock(body)
        } catch (timeout: LockTimeoutException) {
            if (files.isEmpty()) throw timeout
            throw LockTimeoutException(
                "${timeout.message}; ${files.joinToString { it.fileName.toString() }} left unchanged",
                timeout,
            )
        }
    }

    /**
     * The guarded read-modify-write of one file: [transform] sees the text as read, and the
     * replacing rename lands only while the file still matches it. When an external editor rewrote
     * it in between, the cycle is recomputed against the new text instead of overwriting it, so a
     * hand edit made during the write survives — and [transform] itself refuses the recompute when
     * that edit landed inside a managed run.
     *
     * [missing] is what a file that does not exist yet starts from, and null there means an absent
     * file has nothing to change. Returns the text written, or null when [transform] asked for no
     * change. Raises when the file keeps changing under every attempt: refusing is the one answer
     * that cannot lose the other writer's text.
     */
    fun rewrite(file: Path, missing: String? = null, transform: (String) -> String?): String? = mutate(file) {
        repeat(ATTEMPTS) {
            val bytes = if (file.exists()) file.readBytes() else null
            val text = bytes?.decodeToString() ?: missing ?: return@mutate null
            val updated = transform(text) ?: return@mutate null
            if (AtomicWrite.writeIfUnchanged(file, updated, bytes?.let(FileIdentity::of))) return@mutate updated
        }
        error("$file kept being rewritten outside Ruleblend; the change was not applied")
    }

    private companion object {
        /** Enough to ride out an editor's save; a file changing on every attempt is not a race. */
        const val ATTEMPTS = 3
    }
}
