package dev.ruleblend.core.integration

import dev.ruleblend.core.storage.pathIdentity
import dev.ruleblend.core.config.projectKeyOf
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * A disposable index of target files. It is derived only from target-file contents.
 *
 * One index file holds the entries of every target, so two targets written at once are a
 * read-modify-write of the same file: the record goes through [coordinator], and callers that
 * change a target name this file alongside it, so the target and its entry move as one.
 */
class TargetIndex(
    /** Named by whoever mutates a target, so the pair is held under one lock. */
    val file: Path,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) {
    data class Entry(val path: String, val mode: TargetOwnershipMode, val blocks: List<BlockHash>)
    data class BlockHash(val id: String, val hash: String)

    fun load(): List<Entry> = if (!file.exists()) emptyList() else file.readLines().mapNotNull(::parse)

    fun update(path: Path, text: String): Unit = coordinator.mutate(file) {
        val mode = sniffOwnershipMode(text)
        if (mode == TargetOwnershipMode.NONE) return@mutate remove(path)
        val entry = Entry(path.pathIdentity(), mode,
            targetEncoding(text).installed(text).map { BlockHash(it.id, hashContent(it.content)) })
        save(load().filterNot { it.path == entry.path } + entry)
    }

    fun remove(path: Path): Unit = coordinator.mutate(file) {
        val canonical = path.pathIdentity()
        save(load().filterNot { it.path == canonical })
    }

    private fun save(entries: List<Entry>) =
        AtomicWrite.write(file, entries.joinToString("\n", postfix = "\n") { encode(it) })

    private fun encode(entry: Entry): String = listOf(digest(entry.path), entry.mode.name, entry.path,
        entry.blocks.joinToString(",") { "${it.id}:${it.hash}" }).joinToString("\t")

    private fun parse(line: String): Entry? {
        val fields = line.split('\t')
        if (fields.size != 4 || fields[0] != digest(fields[2])) return null
        val mode = runCatching { TargetOwnershipMode.valueOf(fields[1]) }.getOrNull() ?: return null
        val blocks = fields[3].takeIf { it.isNotEmpty() }?.split(',')?.mapNotNull { token ->
            val parts = token.split(':', limit = 2)
            parts.getOrNull(0)?.takeIf(String::isNotEmpty)?.let { id -> parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.let { BlockHash(id, it) } }
        }.orEmpty()
        return Entry(projectKeyOf(fields[2]), mode, blocks)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
