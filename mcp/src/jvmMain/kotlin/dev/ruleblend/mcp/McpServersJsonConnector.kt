package dev.ruleblend.mcp

import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.install.McpServersJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Registers Ruleblend in ZCode's Claude-compatible `~/.agents/mcp.json` table. */
class ZCodeConnector(
    home: Path = Path.of(System.getProperty("user.home")),
    coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) : McpServersJsonConnector(
    agentId = "zcode",
    configFile = home.resolve(".agents").resolve("mcp.json"),
    agentHome = home.resolve(".zcode"),
    coordinator = coordinator,
)

/** Registers Ruleblend in Pi's native user-level `mcp.json`, honoring `PI_CODING_AGENT_DIR`. */
class PiConnector(
    home: Path? = null,
    coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpServersJsonConnector(
    agentId = "pi",
    configFile = paths.pi.resolve("mcp.json"),
    agentHome = paths.pi,
    coordinator = coordinator,
)

/** Registers Ruleblend in a Claude-compatible `mcpServers` table shared by several tools. */
open class McpServersJsonConnector(
    override val agentId: String,
    private val configFile: Path,
    private val agentHome: Path,
    private val coordinator: TargetMutationCoordinator,
) : McpConnector {

    override fun isAvailable(): Boolean = agentHome.exists()

    override fun register(binary: Path) {
        val config = ruleblendEntry(binary)
        coordinator.rewrite(configFile, missing = "") { text ->
            requireWritableRuleblendEntry(entryIn(text), McpServersJson.entryText(text, MCP_SERVER_NAME) != null)
            McpServersJson.upsert(withoutOwnedLegacyRegistration(text), MCP_SERVER_NAME, McpServersJson.renderEntry(config))
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
        val entry = entryIn(current)
            ?: return if (McpServersJson.entryText(current, MCP_SERVER_NAME) != null) McpRegistration.FOREIGN
                else if (hasOwnedLegacyRegistration(current)) McpRegistration.STALE_PATH else McpRegistration.NOT_REGISTERED
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
