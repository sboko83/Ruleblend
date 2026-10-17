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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Root id of every current record: the agent's own skill directory. */
const val NATIVE_SKILL_ROOT: String = "native"

/** What Ruleblend last wrote to one agent's target-specific skill directory. */
@Serializable
data class SkillInstallRecord(
    val targetKey: String,
    val agentId: String,
    val skillId: String,
    val fingerprint: String,
    /**
     * [NATIVE_SKILL_ROOT] for a copy in the agent's own skill directory. Any other value is a copy
     * an earlier version wrote into the cross-agent `.agents/skills` tree, kept only until
     * [SkillInstallService.retireSharedCopies] clears it.
     */
    val directoryId: String = NATIVE_SKILL_ROOT,
    /** Physical directory name; differs after a captured skill was renamed in the library. */
    val directoryName: String = skillId,
    /** `g=<group-id>` or `p=<profile-id>`; absent means the target's base installation. */
    val origin: String? = null,
    /** Portable modes last installed; null denotes a legacy record without mode metadata. */
    val executableFiles: List<String>? = null,
) {
    val isNative: Boolean get() = directoryId == NATIVE_SKILL_ROOT
}

/**
 * What a record without a `directoryId` meant: the agent's single root. That was the agent's own
 * directory for every agent but Pi, whose single root was the shared `.agents/skills` tree.
 */
private const val LEGACY_DEFAULT_ROOT = "default"

/** Sidecar state; losing it degrades ownership detection but never permits an overwrite. */
class SkillInstallStateStore(
    dir: Path = Path.of(System.getProperty("user.home")).resolve(".ruleblend"),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) {
    /** Named by whoever writes a skill tree, so the tree and its record are held as one. */
    val file: Path = dir.resolve("skill-state.json")
    // Every record names its root on disk: an absent id is reserved for records that predate it.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    /** Records of copies in the agents' own skill directories. */
    fun all(): List<SkillInstallRecord> = records().filter { it.isNative }

    /** Records of copies left in the retired shared `.agents/skills` tree. */
    fun legacy(): List<SkillInstallRecord> = records().filterNot { it.isNative }

    fun find(targetKey: String, agentId: String, skillId: String): SkillInstallRecord? =
        all().filter {
            it.targetKey == normalizedPlaceId(targetKey) && it.agentId == agentId && it.skillId == skillId
        }.singleOrNull()

    fun record(record: SkillInstallRecord) = coordinator.mutate(file) {
        val normalized = record.copy(targetKey = normalizedPlaceId(record.targetKey), directoryId = NATIVE_SKILL_ROOT)
        save(records().filterNot { it.sameKey(normalized) } + normalized)
    }

    fun remove(targetKey: String, agentId: String, skillId: String) = coordinator.mutate(file) {
        val records = records()
        val remaining = records.filterNot {
            it.isNative && it.targetKey == normalizedPlaceId(targetKey) && it.agentId == agentId && it.skillId == skillId
        }
        if (remaining.size != records.size) save(remaining)
    }

    /** Drops one record [legacy] returned. */
    fun removeLegacy(record: SkillInstallRecord) = coordinator.mutate(file) {
        val records = records()
        val remaining = records.filterNot { it == record }
        if (remaining.size != records.size) save(remaining)
    }

    /** Drops every record of [skillId] in [targetKey], across agents and roots, in one write. */
    fun removeAll(targetKey: String, skillId: String) = coordinator.mutate(file) {
        val records = records()
        val remaining = records.filterNot { it.targetKey == normalizedPlaceId(targetKey) && it.skillId == skillId }
        if (remaining.size != records.size) save(remaining)
    }

    /** Every record on disk, with a pre-root-id record resolved to the root it described. */
    private fun records(): List<SkillInstallRecord> = FileReadScope.cached(file to "skill-state") {
        val text = FileReadScope.textOrNull(file) ?: return@cached emptyList()
        runCatching {
            json.parseToJsonElement(text).jsonArray.map { element ->
                val fields = element.jsonObject
                val record = json.decodeFromJsonElement<SkillInstallRecord>(fields)
                val stored = fields["directoryId"]?.jsonPrimitive?.content ?: LEGACY_DEFAULT_ROOT
                val directoryId = if (stored == LEGACY_DEFAULT_ROOT && record.agentId != "pi") NATIVE_SKILL_ROOT else stored
                record.copy(targetKey = normalizedPlaceId(record.targetKey), directoryId = directoryId)
            }
        }.getOrDefault(emptyList()).distinct()
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
