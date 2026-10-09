package dev.ruleblend.core.integration

import dev.ruleblend.core.config.normalizedPlaceId
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What Ruleblend last wrote to one agent's target-specific skill directory. */
@Serializable
data class SkillInstallRecord(
    val targetKey: String,
    val agentId: String,
    val skillId: String,
    val fingerprint: String,
    /** Multiple roots of one agent need independent ownership records. Old records mean `default`. */
    val directoryId: String = "default",
    /** Physical directory name; differs after a captured skill was renamed in the library. */
    val directoryName: String = skillId,
    /** `g=<group-id>` or `p=<profile-id>`; absent means the target's base installation. */
    val origin: String? = null,
    /** Portable modes last installed; null denotes a legacy record without mode metadata. */
    val executableFiles: List<String>? = null,
)

/** Sidecar state; losing it degrades ownership detection but never permits an overwrite. */
class SkillInstallStateStore(
    dir: Path = Path.of(System.getProperty("user.home")).resolve(".ruleblend"),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) {
    /** Named by whoever writes a skill tree, so the tree and its record are held as one. */
    val file: Path = dir.resolve("skill-state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun all(): List<SkillInstallRecord> = FileReadScope.cached(file to "skill-state") {
        val text = FileReadScope.textOrNull(file) ?: return@cached emptyList()
        runCatching { json.decodeFromString<List<SkillInstallRecord>>(text) }.getOrDefault(emptyList())
            .map { it.copy(targetKey = normalizedPlaceId(it.targetKey)) }.distinct()
    }

    fun find(
        targetKey: String,
        agentId: String,
        skillId: String,
        directoryId: String = "default",
    ): SkillInstallRecord? =
        all().filter {
            it.targetKey == normalizedPlaceId(targetKey) &&
                it.agentId == agentId &&
                it.skillId == skillId &&
                it.directoryId == directoryId
        }.singleOrNull()

    fun record(record: SkillInstallRecord) = coordinator.mutate(file) {
        val normalized = record.copy(targetKey = normalizedPlaceId(record.targetKey))
        save(all().filterNot { it.sameKey(normalized) } + normalized)
    }

    fun remove(
        targetKey: String,
        agentId: String,
        skillId: String,
        directoryId: String = "default",
    ) = coordinator.mutate(file) {
        val records = all()
        val remaining = records.filterNot {
            it.targetKey == normalizedPlaceId(targetKey) &&
                it.agentId == agentId &&
                it.skillId == skillId &&
                it.directoryId == directoryId
        }
        if (remaining.size != records.size) save(remaining)
    }

    /** Drops every record of [skillId] in [targetKey], across agents and directories, in one write. */
    fun removeAll(targetKey: String, skillId: String) = coordinator.mutate(file) {
        val records = all()
        val remaining = records.filterNot { it.targetKey == normalizedPlaceId(targetKey) && it.skillId == skillId }
        if (remaining.size != records.size) save(remaining)
    }

    private fun SkillInstallRecord.sameKey(other: SkillInstallRecord): Boolean =
        targetKey == other.targetKey &&
            agentId == other.agentId &&
            skillId == other.skillId &&
            directoryId == other.directoryId

    private fun save(records: List<SkillInstallRecord>) {
        AtomicWrite.write(file, json.encodeToString(records) + "\n")
    }
}

internal fun skillTargetKey(target: Target): String = when (target) {
    is AgentGlobalTarget -> "agent"
    is ProjectTarget -> "project:${target.dir.projectKey()}"
}
