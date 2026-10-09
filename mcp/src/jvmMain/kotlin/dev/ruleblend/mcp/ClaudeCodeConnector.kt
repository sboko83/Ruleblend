package dev.ruleblend.mcp

import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.install.McpServersJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Registers the MCP server in Claude Code's user scope: the top-level `mcpServers` object of
 * `~/.claude.json`. Everything else in that file is preserved structurally.
 */
class ClaudeCodeConnector(
    home: Path? = null,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpConnector {

    override val agentId: String = "claude-code"

    private val configFile: Path get() = paths.claudeConfig

    override fun isAvailable(): Boolean = paths.claude.exists()

    override fun register(binary: Path) {
        val entry = McpServersJson.renderEntry(ruleblendEntry(binary))
        coordinator.rewrite(configFile, missing = "") { text ->
            requireWritableRuleblendEntry(entryIn(text), McpServersJson.entryText(text, MCP_SERVER_NAME) != null)
            McpServersJson.upsert(withoutOwnedLegacyRegistration(text), MCP_SERVER_NAME, entry)
        }
    }

    override fun unregister() {
        coordinator.rewrite(configFile) { text ->
            requireWritableRuleblendEntry(entryIn(text), McpServersJson.entryText(text, MCP_SERVER_NAME) != null)
            withoutOwnedLegacyRegistration(McpServersJson.remove(text, MCP_SERVER_NAME)).takeIf { it != text }
        }
    }

    override fun status(binary: Path): McpRegistration {
        val current = load()
        val entry = entryIn(current) ?: return if (McpServersJson.entryText(current, MCP_SERVER_NAME) != null) {
            McpRegistration.FOREIGN
        } else if (hasOwnedLegacyRegistration(current)) {
            McpRegistration.STALE_PATH
        } else {
            McpRegistration.NOT_REGISTERED
        }
        return registrationOf(entry, binary)
    }

    override fun installedEntryVersion(): Int? {
        val current = load()
        val entry = entryIn(current) as? McpServerConfig.Stdio
            ?: return if (hasOwnedLegacyRegistration(current)) 0 else null
        return entry.takeIf { it.isOwnedRuleblendEntry() }?.entryVersion()
    }

    private fun entryIn(text: String): McpServerConfig? =
        McpServersJson.read(text, MCP_SERVER_NAME)?.let(McpServersJson::parseEntry)

    private fun load(): String = if (configFile.exists()) configFile.readText() else ""

    private fun hasOwnedLegacyRegistration(text: String): Boolean = McpServersJson
        .read(text, LEGACY_MCP_SERVER_NAME)
        ?.let(McpServersJson::parseEntry)
        ?.let { it as? McpServerConfig.Stdio }
        ?.isLegacyRuleblendRegistration() == true

    private fun withoutOwnedLegacyRegistration(text: String): String =
        if (hasOwnedLegacyRegistration(text)) McpServersJson.remove(text, LEGACY_MCP_SERVER_NAME) else text
}
