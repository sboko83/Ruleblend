package dev.ruleblend.mcp

import dev.ruleblend.core.integration.KimiCodeHome
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.mcp.install.KimiMcpJson
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Registers Ruleblend in Kimi Code's user-level `$KIMI_CODE_HOME/mcp.json`. */
class KimiCodeConnector(
    home: KimiCodeHome = KimiCodeHome.current(),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) : McpConnector {
    /** [home] is the operating-system home, see [KimiCodeHome.under]. */
    constructor(home: Path, coordinator: TargetMutationCoordinator = TargetMutationCoordinator()) :
        this(KimiCodeHome.under(home), coordinator)

    private val kimiHome: Path = home.directory

    override val agentId: String = "kimi-code"

    private val configFile: Path get() = kimiHome.resolve("mcp.json")

    override fun isAvailable(): Boolean = kimiHome.exists()

    override fun register(binary: Path) {
        val config = ruleblendEntry(binary)
        coordinator.rewrite(configFile, missing = "") { text ->
            requireWritableRuleblendEntry(KimiMcpJson.read(text, MCP_SERVER_NAME),
                KimiMcpJson.entryText(text, MCP_SERVER_NAME) != null)
            KimiMcpJson.upsert(withoutOwnedLegacyRegistration(text), MCP_SERVER_NAME, config)
        }
    }

    override fun unregister() {
        coordinator.rewrite(configFile) { text ->
            requireWritableRuleblendEntry(KimiMcpJson.read(text, MCP_SERVER_NAME),
                KimiMcpJson.entryText(text, MCP_SERVER_NAME) != null)
            withoutOwnedLegacyRegistration(KimiMcpJson.remove(text, MCP_SERVER_NAME)).takeIf { it != text }
        }
    }

    override fun status(binary: Path): McpRegistration {
        val current = load()
        val entry = KimiMcpJson.read(current, MCP_SERVER_NAME) ?: return if (KimiMcpJson.entryText(current, MCP_SERVER_NAME) != null) {
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
        val entry = KimiMcpJson.read(current, MCP_SERVER_NAME) as? McpServerConfig.Stdio
            ?: return if (hasOwnedLegacyRegistration(current)) 0 else null
        return entry.takeIf { it.isOwnedRuleblendEntry() }?.entryVersion()
    }

    private fun load(): String = if (configFile.exists()) configFile.readText() else ""

    private fun hasOwnedLegacyRegistration(text: String): Boolean = KimiMcpJson
        .read(text, LEGACY_MCP_SERVER_NAME)
        ?.let { it as? McpServerConfig.Stdio }
        ?.isLegacyRuleblendRegistration() == true

    private fun withoutOwnedLegacyRegistration(text: String): String =
        if (hasOwnedLegacyRegistration(text)) KimiMcpJson.remove(text, LEGACY_MCP_SERVER_NAME) else text
}
