package dev.ruleblend.core.integration

import dev.ruleblend.core.storage.AtomicWrite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/** What a write over a whole target did to one of its files. */
enum class TargetWriteOutcome {
    /** The file now holds the change. */
    WRITTEN,

    /** The file already said what the change wanted it to say; nothing was written. */
    UNCHANGED,

    /** The write threw here, and the files after it were not attempted. */
    FAILED,

    /** The write landed here and was undone after a later file failed. */
    ROLLED_BACK,

    /** Undoing this file failed too: it still holds the change while the operation did not finish. */
    STRANDED,
}

/**
 * Per-file result of one write over a target.
 *
 * A project target can span several agents, and their files are written one after another. The
 * result says what happened to each of them rather than throwing away everything but the first
 * failure: a caller that only knows "it threw" cannot tell whether the first agent was left holding
 * a rule the second one refused.
 */
data class TargetWrite(
    val file: Path,
    val outcome: TargetWriteOutcome,
    /** Why this file failed, when it did. */
    val error: String? = null,
)

/** Everything one whole-target write did, in file order. */
data class TargetWriteResult(val writes: List<TargetWrite>) {

    val failed: List<TargetWrite> get() = writes.filter { it.outcome == TargetWriteOutcome.FAILED }

    /** Files that were undone after a later failure, and files that could not be undone. */
    val rolledBack: List<TargetWrite> get() = writes.filter { it.outcome == TargetWriteOutcome.ROLLED_BACK }
    val stranded: List<TargetWrite> get() = writes.filter { it.outcome == TargetWriteOutcome.STRANDED }

    /** True when every file of the target ended where the caller asked it to. */
    val isComplete: Boolean get() = failed.isEmpty() && stranded.isEmpty()

    /**
     * The failure to report, or `null` when the write finished. The message names each file and what
     * became of it, because "some of it landed" is the one thing the user has to be told exactly.
     */
    fun problem(): TargetWriteException? {
        if (isComplete) return null
        val detail = buildString {
            failed.forEach { append("${it.file}: ${it.error ?: "failed"}; ") }
            if (rolledBack.isNotEmpty()) append("undone in ${rolledBack.joinToString { it.file.toString() }}; ")
            if (stranded.isNotEmpty()) append("still changed in ${stranded.joinToString { it.file.toString() }}; ")
        }.trimEnd(' ', ';')
        return TargetWriteException(this, detail)
    }
}

/** A whole-target write that did not finish, carrying what became of every file it touched. */
class TargetWriteException(val result: TargetWriteResult, message: String) : RuntimeException(message)

/**
 * Snapshot of a set of files written as one operation, and the way back to it.
 *
 * Two agents of one project are two files, and a write that lands in the first and fails on the
 * second leaves the target holding half an answer. Restoring is best effort by nature — the reason
 * the second write failed may well stop the first from being put back — so what could not be undone
 * is returned rather than swallowed, and the caller reports it.
 */
class FileRollback(files: List<Path>) {

    private val before: Map<Path, ByteArray?> = files.associateWith {
        if (it.exists()) Files.readAllBytes(it) else null
    }

    /** Puts every file back as it was. Returns the files that could not be restored. */
    fun restoreAll(): List<Path> = before.mapNotNull { (file, bytes) ->
        val restored = runCatching {
            if (bytes == null) Files.deleteIfExists(file) else AtomicWrite.write(file, bytes)
        }.isSuccess
        file.takeUnless { restored }
    }
}
