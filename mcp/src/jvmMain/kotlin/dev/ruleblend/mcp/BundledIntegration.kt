package dev.ruleblend.mcp

import dev.ruleblend.mcp.skill.BundledSkill
import dev.ruleblend.mcp.skill.SkillInstaller
import dev.ruleblend.mcp.skill.SkillStatus
import java.nio.file.Path

/** The bundled skill in one agent's skills directory, against the one this app ships. */
data class BundledSkillState(
    val status: SkillStatus,
    val installed: Int?,
    val current: Int = BundledSkill.version,
) {
    val outdated: Boolean get() = status == SkillStatus.OUTDATED
}

/** Ruleblend's own MCP registration in one agent's config, against the entry this app writes. */
data class BundledMcpState(
    val registration: McpRegistration,
    val installed: Int?,
    val current: Int = MCP_ENTRY_VERSION,
) {
    val outdated: Boolean
        get() = registration == McpRegistration.OUTDATED || registration == McpRegistration.STALE_PATH
}

/**
 * What Ruleblend put into one agent and whether it is still current. [mcp] is `null` when this
 * agent has no connector or when there is no server binary to point at;
 * [skill] is `null` when the agent has no skills directory of its own.
 */
data class AgentIntegrationState(
    val agentId: String,
    val mcp: BundledMcpState? = null,
    val skill: BundledSkillState? = null,
) {
    /** Something of ours is installed here and an app update left it behind. */
    val outdated: Boolean get() = mcp?.outdated == true || skill?.outdated == true

    /** A skill of the same name that Ruleblend did not write. Never touched, only reported. */
    val blocked: Boolean get() = skill?.status == SkillStatus.FOREIGN
}

/**
 * The single answer to "what does this machine's agents have of ours, and is it current".
 *
 * Home and Settings both ask it. They used to ask the connectors and the installers separately,
 * which is how one surface can start disagreeing with the other about whether an update is due —
 * the same reason the install use cases were pulled out of the facades.
 */
class BundledIntegration(
    private val connectors: List<McpConnector> = emptyList(),
    private val skillInstallers: List<SkillInstaller> = emptyList(),
    /** The launcher every registration points at; `null` in a run that could not resolve one. */
    private val binary: Path? = null,
) {

    /** State per agent present on this machine, in the order the connectors were given. */
    fun states(): List<AgentIntegrationState> {
        val agentIds = LinkedHashSet<String>()
        connectors.filter { it.isAvailable() }.mapTo(agentIds) { it.agentId }
        skillInstallers.filter { it.isAvailable() }.mapTo(agentIds) { it.agentId }
        return agentIds.map { agentId ->
            AgentIntegrationState(
                agentId = agentId,
                mcp = mcpStateOf(agentId),
                skill = skillStateOf(agentId),
            )
        }
    }

    fun stateOf(agentId: String): AgentIntegrationState? = states().find { it.agentId == agentId }

    /**
     * Rewrites whatever is outdated for one agent, and nothing else: an entry that was never
     * registered stays unregistered, and a foreign skill stays the user's file.
     */
    fun update(agentId: String) {
        val state = stateOf(agentId) ?: return
        if (state.mcp?.outdated == true) binary?.let { connectorFor(agentId)?.register(it) }
        if (state.skill?.outdated == true) skillInstallerFor(agentId)?.install()
    }

    /** The same for every agent that needs it. Returns the agent ids it touched. */
    fun updateAll(): List<String> = states().filter { it.outdated }.map { it.agentId }.onEach(::update)

    private fun mcpStateOf(agentId: String): BundledMcpState? {
        val connector = connectorFor(agentId) ?: return null
        val path = binary ?: return null
        return BundledMcpState(connector.status(path), connector.installedEntryVersion())
    }

    private fun skillStateOf(agentId: String): BundledSkillState? {
        val installer = skillInstallerFor(agentId) ?: return null
        return BundledSkillState(installer.status(), installer.installedVersion())
    }

    private fun connectorFor(agentId: String): McpConnector? =
        connectors.find { it.agentId == agentId && it.isAvailable() }

    private fun skillInstallerFor(agentId: String): SkillInstaller? =
        skillInstallers.find { it.agentId == agentId && it.isAvailable() }
}
