package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.LEGACY_MCP_SERVER_NAME
import dev.ruleblend.mcp.MCP_SERVER_NAME
import dev.ruleblend.mcp.isLegacyRuleblendRegistration
import dev.ruleblend.mcp.isOwnedRuleblendEntry
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.JsonObject

/** Moves only proven owned entries. Project `.mcp.json` remains available to Claude Code. */
class ZCodeMcpMigration(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val state: McpStateStore = McpStateStore(home.resolve(".ruleblend"), coordinator),
) {
    fun migrate(): Int {
        var moved = 0
        state.all().filter { it.agentId == "zcode" && it.configPath == null }.forEach { record ->
            attempt(record.blockId) {
                val project = record.targetKey.takeIf { it.startsWith("project:") }
                    ?.removePrefix("project:")?.let(Path::of)
                if (record.targetKey != "agent" && (project == null || !project.isAbsolute)) return@attempt false
                val source = project?.resolve(".mcp.json") ?: home.resolve(".agents/mcp.json")
                val destination = project?.resolve(".zcode/config.json") ?: home.resolve(".zcode/cli/config.json")
                move(source, destination, record.entryName ?: record.blockId,
                    owned = { it == McpServersJson.renderEntry(record.config) },
                    removeSource = project == null,
                    recorded = { state.record(record.copy(configPath = destination.toString())) },
                )
            }.also { if (it) moved++ }
        }
        // The built-in entry has its own ownership stamp, independent of the library sidecar.
        for (name in listOf(MCP_SERVER_NAME, LEGACY_MCP_SERVER_NAME)) {
            attempt(name) {
                move(home.resolve(".agents/mcp.json"), home.resolve(".zcode/cli/config.json"), name,
                    owned = { entry ->
                        val config = McpServersJson.parseEntry(entry)
                        if (name == MCP_SERVER_NAME) config?.isOwnedRuleblendEntry() == true
                        else (config as? McpServerConfig.Stdio)?.isLegacyRuleblendRegistration() == true
                    },
                    removeSource = true,
                )
            }.also { if (it) moved++ }
        }
        return moved
    }

    private fun attempt(name: String, action: () -> Boolean): Boolean = runCatching(action)
        .onFailure { System.err.println("Ruleblend: ZCode MCP migration skipped '$name': ${it.message}") }
        .getOrDefault(false)

    private fun move(
        source: Path,
        destination: Path,
        name: String,
        owned: (JsonObject) -> Boolean,
        removeSource: Boolean,
        recorded: () -> Unit = {},
    ): Boolean = coordinator.mutate(source, destination, state.file) {
        if (!source.exists()) return@mutate false
        val entry = McpServersJson.read(FileReadScope.text(source), name) ?: return@mutate false
        if (!owned(entry)) return@mutate false
        coordinator.rewrite(destination, missing = "") { text ->
            val present = ZCodeMcpJson.entryText(text, name) != null
            require(!present || ZCodeMcpJson.read(text, name) == entry) {
                "a different '$name' already exists in $destination"
            }
            ZCodeMcpJson.upsert(text, name, entry)
        }
        // Record the new address before retiring the old copy, so an interrupted move is retryable.
        recorded()
        if (removeSource) coordinator.rewrite(source) { text ->
            if (McpServersJson.read(text, name) == entry) McpServersJson.remove(text, name) else null
        }
        true
    }
}
