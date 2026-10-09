package dev.ruleblend.core.integration

import dev.ruleblend.core.config.normalizedPlaceId
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bytes Ruleblend last accepted for one file-backed subagent definition. */
@Serializable
data class SubagentInstallRecord(
    val targetKey: String,
    val agentId: String,
    val subagentId: String,
    val fingerprint: String,
    /** `g=<group-id>` or `p=<profile-id>`; absent means the target's base installation. */
    val origin: String? = null,
    /** Physical file stem when a foreign definition was adopted under a different library id. */
    val fileName: String? = null,
    /**
     * Fingerprint of the library render that [fingerprint]'s bytes were found equivalent to when
     * adopted: same fields and body, other layout. While both still hold, the file is in sync.
     */
    val equivalentRender: String? = null,
)

/**
 * Sidecar ownership state for subagent files. Losing it never permits an overwrite: an unknown
 * file remains a conflict until its bytes match the current renderer or the user adopts it.
 */
class SubagentInstallStateStore(
    dir: Path = Path.of(System.getProperty("user.home")).resolve(".ruleblend"),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) {
    /** Named by the installer so a file write and its ownership record share one mutation. */
    val file: Path = dir.resolve("subagent-state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun all(): List<SubagentInstallRecord> = FileReadScope.cached(file to "subagent-state") {
        val text = FileReadScope.textOrNull(file) ?: return@cached emptyList()
        runCatching { json.decodeFromString<List<SubagentInstallRecord>>(text) }.getOrDefault(emptyList())
            .map { it.copy(targetKey = normalizedPlaceId(it.targetKey)) }.distinct()
    }

    fun find(targetKey: String, agentId: String, subagentId: String): SubagentInstallRecord? =
        all().filter { it.targetKey == normalizedPlaceId(targetKey) && it.agentId == agentId && it.subagentId == subagentId }
            .singleOrNull()

    fun record(record: SubagentInstallRecord) = coordinator.mutate(file) {
        val normalized = record.copy(targetKey = normalizedPlaceId(record.targetKey))
        save(all().filterNot { it.sameKey(normalized) } + normalized)
    }

    fun remove(targetKey: String, agentId: String, subagentId: String) = coordinator.mutate(file) {
        val records = all()
        val remaining = records.filterNot {
            it.targetKey == normalizedPlaceId(targetKey) && it.agentId == agentId && it.subagentId == subagentId
        }
        if (remaining.size != records.size) save(remaining)
    }

    /** Drops every agent's record of [subagentId] in [targetKey] in one write. */
    fun removeAll(targetKey: String, subagentId: String) = coordinator.mutate(file) {
        val records = all()
        val remaining = records.filterNot { it.targetKey == normalizedPlaceId(targetKey) && it.subagentId == subagentId }
        if (remaining.size != records.size) save(remaining)
    }

    private fun SubagentInstallRecord.sameKey(other: SubagentInstallRecord): Boolean =
        targetKey == other.targetKey && agentId == other.agentId && subagentId == other.subagentId

    private fun save(records: List<SubagentInstallRecord>) {
        AtomicWrite.write(file, json.encodeToString(records) + "\n")
    }
}
