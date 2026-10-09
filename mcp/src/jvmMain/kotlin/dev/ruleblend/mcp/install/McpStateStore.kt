package dev.ruleblend.mcp.install

import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.normalizedPlaceId
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What Ruleblend last wrote for one block into one agent's MCP config. JSON/TOML entries carry no
 * markers or versions, so without this record MODIFIED and UPDATE_AVAILABLE are indistinguishable.
 */
@Serializable
data class McpInstallRecord(
    val targetKey: String,
    val agentId: String,
    val blockId: String,
    val version: Int,
    val config: McpServerConfig,
    /** `g=<group-id>` or `p=<profile-id>`; absent means the target's base installation. */
    val origin: String? = null,
    /**
     * Native config key when a foreign entry was saved under a different library id. Absent means
     * the usual [blockId] key, keeping records written before foreign-entry adoption readable.
     */
    val entryName: String? = null,
)

/** Sidecar store at `~/.ruleblend/mcp-state.json`. Losing it degrades statuses, never correctness. */
class McpStateStore(
    dir: Path = Path.of(System.getProperty("user.home")).resolve(".ruleblend"),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) {

    /** Named by whoever writes an MCP entry, so the entry and its record are held as one. */
    val file: Path = dir.resolve("mcp-state.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun all(): List<McpInstallRecord> = FileReadScope.cached(file to "mcp-state") {
        val text = FileReadScope.textOrNull(file) ?: return@cached emptyList()
        runCatching { json.decodeFromString<List<McpInstallRecord>>(text) }
            .getOrDefault(emptyList())
            // Records written before project keys were normalized are read under the key this build
            // asks with; the file itself is repaired by the next write rather than on every read.
            .map { record -> record.copy(targetKey = normalizedTargetKey(record.targetKey)) }.distinct()
    }

    fun find(targetKey: String, agentId: String, blockId: String): McpInstallRecord? =
        all().filter { it.targetKey == normalizedTargetKey(targetKey) && it.agentId == agentId && it.blockId == blockId }
            .singleOrNull()

    fun record(record: McpInstallRecord) = coordinator.mutate(file) {
        val normalized = record.copy(targetKey = normalizedTargetKey(record.targetKey))
        val rest = all().filterNot {
            it.targetKey == normalized.targetKey && it.agentId == normalized.agentId && it.blockId == normalized.blockId
        }
        save(rest + normalized)
    }

    fun remove(targetKey: String, blockId: String) = coordinator.mutate(file) {
        val records = all()
        val remaining = records.filterNot { it.targetKey == normalizedTargetKey(targetKey) && it.blockId == blockId }
        if (remaining.size != records.size) save(remaining)
    }

    private fun save(records: List<McpInstallRecord>) {
        AtomicWrite.write(file, json.encodeToString(records) + "\n")
    }
}

/**
 * Stable key for a target: agent-global installs share one namespace, projects one per folder. The
 * project half is the same normalized key the config uses, so a record written after opening a
 * project by one spelling of its path is still found after opening it by another.
 */
fun targetKey(target: Target): String = when (target) {
    is AgentGlobalTarget -> "agent"
    is ProjectTarget -> "project:${target.dir.projectKey()}"
}

/** The same key for one already stored as text, so an older state file still answers. */
private fun normalizedTargetKey(key: String): String = normalizedPlaceId(key)
