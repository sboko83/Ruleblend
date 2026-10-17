package dev.ruleblend.mcp

import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.install.ZCodeMcpJson
import dev.ruleblend.mcp.install.McpServersJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Registers Ruleblend in ZCode's native `~/.zcode/cli/config.json` at `mcp.servers`. */
class ZCodeConnector(
    home: Path = Path.of(System.getProperty("user.home")),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) : McpConnector {
    private val agentHome = home.resolve(".zcode")
    private val configFile = agentHome.resolve("cli").resolve("config.json")

    override val agentId: String = "zcode"

    override fun isAvailable(): Boolean = agentHome.exists()

    override fun register(binary: Path) {
        val config = ruleblendEntry(binary)
        coordinator.rewrite(configFile, missing = "") { text ->
            requireWritableRuleblendEntry(entryIn(text, MCP_SERVER_NAME),
                ZCodeMcpJson.entryText(text, MCP_SERVER_NAME) != null)
            ZCodeMcpJson.upsert(withoutOwnedLegacyRegistration(text), MCP_SERVER_NAME, McpServersJson.renderEntry(config))
        }
    }

    override fun unregister() {
        coordinator.rewrite(configFile) { text ->
            requireWritableRuleblendEntry(entryIn(text, MCP_SERVER_NAME),
                ZCodeMcpJson.entryText(text, MCP_SERVER_NAME) != null)
            withoutOwnedLegacyRegistration(ZCodeMcpJson.remove(text, MCP_SERVER_NAME)).takeIf { it != text }
        }
    }

    override fun status(binary: Path): McpRegistration {
        val current = load()
        val entry = entryIn(current, MCP_SERVER_NAME) ?: return if (ZCodeMcpJson.entryText(current, MCP_SERVER_NAME) != null) {
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
        val entry = entryIn(current, MCP_SERVER_NAME) as? McpServerConfig.Stdio
            ?: return if (hasOwnedLegacyRegistration(current)) 0 else null
        return entry.takeIf { it.isOwnedRuleblendEntry() }?.entryVersion()
    }

    private fun entryIn(text: String, name: String): McpServerConfig? =
        ZCodeMcpJson.read(text, name)?.let(McpServersJson::parseEntry)

    private fun load(): String = if (configFile.exists()) configFile.readText() else ""

    private fun hasOwnedLegacyRegistration(text: String): Boolean = ZCodeMcpJson
        .read(text, LEGACY_MCP_SERVER_NAME)
        ?.let(McpServersJson::parseEntry)
        ?.let { it as? McpServerConfig.Stdio }
        ?.isLegacyRuleblendRegistration() == true

    private fun withoutOwnedLegacyRegistration(text: String): String =
        if (hasOwnedLegacyRegistration(text)) ZCodeMcpJson.remove(text, LEGACY_MCP_SERVER_NAME) else text
}
