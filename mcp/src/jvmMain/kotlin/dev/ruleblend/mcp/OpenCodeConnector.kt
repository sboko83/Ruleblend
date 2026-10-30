package dev.ruleblend.mcp

import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.install.OpenCodeMcpJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Registers Ruleblend in OpenCode v2's global `<config root>/opencode.json` at `mcp.servers`.
 * A sibling `opencode.jsonc` is never read or rewritten.
 */
class OpenCodeConnector(
    home: Path? = null,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpConnector {
    private val configFile = paths.openCode.resolve("opencode.json")

    override val agentId: String = "opencode"

    override fun isAvailable(): Boolean = paths.openCode.exists()

    override fun register(binary: Path) {
        val config = ruleblendEntry(binary)
        coordinator.rewrite(configFile, missing = "") { text ->
            requireWritableRuleblendEntry(entryIn(text), OpenCodeMcpJson.entryText(text, MCP_SERVER_NAME) != null)
            OpenCodeMcpJson.upsert(text, MCP_SERVER_NAME, OpenCodeMcpJson.renderEntry(config))
        }
    }

    override fun unregister() {
        coordinator.rewrite(configFile) { text ->
            requireWritableRuleblendEntry(entryIn(text), OpenCodeMcpJson.entryText(text, MCP_SERVER_NAME) != null)
            OpenCodeMcpJson.remove(text, MCP_SERVER_NAME).takeIf { it != text }
        }
    }

    override fun status(binary: Path): McpRegistration {
        val current = load()
        val entry = entryIn(current) ?: return if (OpenCodeMcpJson.entryText(current, MCP_SERVER_NAME) != null) {
            McpRegistration.FOREIGN
        } else {
            McpRegistration.NOT_REGISTERED
        }
        return registrationOf(entry, binary)
    }

    override fun installedEntryVersion(): Int? {
        val entry = entryIn(load()) as? McpServerConfig.Stdio ?: return null
        return entry.takeIf { it.isOwnedRuleblendEntry() }?.entryVersion()
    }

    private fun entryIn(text: String): McpServerConfig? =
        OpenCodeMcpJson.read(text, MCP_SERVER_NAME)?.let(OpenCodeMcpJson::parseEntry)

    private fun load(): String = if (configFile.exists()) configFile.readText() else ""
}
