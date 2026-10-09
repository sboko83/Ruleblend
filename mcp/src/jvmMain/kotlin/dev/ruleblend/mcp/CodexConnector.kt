package dev.ruleblend.mcp

import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.install.CodexMcpTable
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Registers the MCP server in Codex's `~/.codex/config.toml` as an `[mcp_servers.ruleblend]` table.
 * Edits are line-based ([CodexMcpTable]) — the existing section is replaced, everything else
 * including comments is preserved verbatim — so no TOML dependency is needed.
 */
class CodexConnector(
    home: Path? = null,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpConnector {

    override val agentId: String = "codex"

    private val configFile: Path get() = paths.codex.resolve("config.toml")

    override fun isAvailable(): Boolean = paths.codex.exists()

    override fun register(binary: Path) {
        val config = ruleblendEntry(binary)
        coordinator.rewrite(configFile, missing = "") { text ->
            requireWritableRuleblendEntry(CodexMcpTable.read(text, MCP_SERVER_NAME),
                CodexMcpTable.entryText(text, MCP_SERVER_NAME) != null)
            CodexMcpTable.upsert(withoutOwnedLegacyRegistration(text), MCP_SERVER_NAME, config)
        }
    }

    override fun unregister() {
        coordinator.rewrite(configFile) { text ->
            requireWritableRuleblendEntry(CodexMcpTable.read(text, MCP_SERVER_NAME),
                CodexMcpTable.entryText(text, MCP_SERVER_NAME) != null)
            withoutOwnedLegacyRegistration(CodexMcpTable.remove(text, MCP_SERVER_NAME)).takeIf { it != text }
        }
    }

    override fun status(binary: Path): McpRegistration {
        val current = load()
        val section = CodexMcpTable.read(current, MCP_SERVER_NAME) ?: return if (CodexMcpTable.entryText(current, MCP_SERVER_NAME) != null) {
            McpRegistration.FOREIGN
        } else if (hasOwnedLegacyRegistration(current)) {
            McpRegistration.STALE_PATH
        } else {
            McpRegistration.NOT_REGISTERED
        }
        return registrationOf(section, binary)
    }

    override fun installedEntryVersion(): Int? {
        val current = load()
        val entry = CodexMcpTable.read(current, MCP_SERVER_NAME) as? McpServerConfig.Stdio
            ?: return if (hasOwnedLegacyRegistration(current)) 0 else null
        return entry.takeIf { it.isOwnedRuleblendEntry() }?.entryVersion()
    }

    private fun load(): String = if (configFile.exists()) configFile.readText() else ""

    private fun hasOwnedLegacyRegistration(text: String): Boolean = CodexMcpTable
        .read(text, LEGACY_MCP_SERVER_NAME)
        ?.let { it as? McpServerConfig.Stdio }
        ?.isLegacyRuleblendRegistration() == true

    private fun withoutOwnedLegacyRegistration(text: String): String =
        if (hasOwnedLegacyRegistration(text)) CodexMcpTable.remove(text, LEGACY_MCP_SERVER_NAME) else text
}
