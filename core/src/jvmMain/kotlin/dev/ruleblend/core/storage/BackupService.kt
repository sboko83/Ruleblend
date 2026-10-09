package dev.ruleblend.core.storage

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One stored snapshot. [timestampMillis] is when the backup was taken; it is what the UI shows and
 * what the restore dialog anchors its confirmation on. There is exactly one slot per path: a new
 * backup replaces the previous one.
 */
@Serializable
data class BackupRecord(val path: String, val timestampMillis: Long)

/**
 * Manual, per-file backup snapshots for the Integration tab's "Found in project/agent" pane. Each
 * path gets one slot under [root]; `restore` writes the snapshot back verbatim through
 * [AtomicWrite], so a restore is an exact rollback to the bytes that were on disk at backup time —
 * including any managed regions Ruleblend had installed. Whole-file snapshots (not just the unmanaged
 * text), because "roll back at any moment" must undo installs and adopts too.
 *
 * GUI-only: the MCP server never calls into this, so there is no `--mcp` stdout concern and no need
 * for the cross-process lock — a restore is a single atomic write, the same shape as an install.
 */
class BackupService(private val root: Path) {

    /**
     * Forward- and backward-compatible: a hand-edited or newer-build index must not stop the service.
     * `ignoreUnknownKeys` drops unknown fields; `coerceInputValues` falls back on a bad enum/value.
     */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }
    private val listSerializer = ListSerializer(BackupRecord.serializer())

    /** Snapshots [path] into its single slot, overwriting any previous snapshot. */
    fun backup(path: Path) {
        val text = if (path.exists()) path.readText() else ""
        val storedPath = record(path)?.path?.let(Path::of) ?: path
        AtomicWrite.write(blob(storedPath), text)
        writeIndex { records ->
            records.filterNot { it.matches(path) } + BackupRecord(storedPath.toString(), System.currentTimeMillis())
        }
    }

    /** Writes the snapshot back to [path]; a no-op when there is no slot. */
    fun restore(path: Path) {
        val blob = blob(record(path)?.path?.let(Path::of) ?: path)
        if (!blob.exists()) return
        AtomicWrite.write(path, blob.readText())
    }

    /** The snapshot for [path], or `null` when none has been taken. */
    fun record(path: Path): BackupRecord? = readIndex().filter { it.matches(path) }.maxByOrNull { it.timestampMillis }

    /** Drops the snapshot for [path] when one exists. */
    fun forget(path: Path) {
        writeIndex { records ->
            val matching = records.filter { it.matches(path) }
            if (matching.isEmpty()) return@writeIndex records
            matching.forEach { blob(Path.of(it.path)).toFile().delete() }
            records - matching.toSet()
        }
    }

    private fun blob(path: Path): Path = root.resolve("${digest(path.toString())}.txt")

    // Keep the original blob address: old hashes are based on the spelling used at backup time.
    private fun BackupRecord.matches(candidate: Path): Boolean =
        runCatching { Path.of(path).pathIdentity() == candidate.pathIdentity() }.getOrDefault(false)

    /**
     * The index, or nothing when it cannot be read. An unreadable index costs the user the list of
     * snapshots, never the snapshots themselves — the blobs are separate files, and the next backup
     * writes a fresh index rather than failing the action that asked for one.
     */
    private fun readIndex(): List<BackupRecord> {
        val file = root.resolve(INDEX_FILE)
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
    }

    /** Read-modify-writes the index through [transform]; drops the index file when the result is empty. */
    private fun writeIndex(transform: (List<BackupRecord>) -> List<BackupRecord>) {
        val updated = transform(readIndex())
        val file = root.resolve(INDEX_FILE)
        if (updated.isEmpty()) {
            file.toFile().delete()
        } else {
            AtomicWrite.write(file, json.encodeToString(listSerializer, updated) + "\n")
        }
    }

    /** SHA-1 of the absolute path string; stable, collision-free for realistic path sets. */
    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val INDEX_FILE = "index.json"
    }
}
