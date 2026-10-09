package dev.ruleblend.core.integration

import java.nio.file.Path

/** A capability advertised by an adapter, including an explicit limit where one scope is absent. */
enum class CapabilityState { SUPPORTED, PARTIAL, UNSUPPORTED }

/** Why a capability is partial or absent. Only values an adapter can actually derive exist here. */
enum class CapabilityLimit {
    GLOBAL_ONLY,
    PROJECT_ONLY,
    NOT_SUPPORTED,
}

data class AgentCapability(
    val state: CapabilityState,
    val limit: CapabilityLimit? = null,
)

data class AgentCapabilities(
    val rules: AgentCapability,
    val mcp: AgentCapability,
    val skills: AgentCapability,
    val subagents: AgentCapability,
)

/**
 * The matrix is derived from the same destinations installers use. A partial answer is deliberate:
 * a global-only definition surface must not be presented as project support.
 */
fun AgentAdapter.capabilities(): AgentCapabilities = AgentCapabilities(
    rules = AgentCapability(CapabilityState.SUPPORTED),
    mcp = AgentCapability(CapabilityState.SUPPORTED),
    skills = scopedCapability(globalSkillDirectories().isNotEmpty(), projectSkillDirectories(Path.of("")).isNotEmpty()),
    subagents = when {
        subagentSupport == SubagentSupport.UNSUPPORTED ->
            AgentCapability(CapabilityState.UNSUPPORTED, CapabilityLimit.NOT_SUPPORTED)
        else -> scopedCapability(globalSubagentDirectory() != null, projectSubagentDirectory(Path.of("")) != null)
    },
)

private fun scopedCapability(global: Boolean, project: Boolean): AgentCapability = when {
    global && project -> AgentCapability(CapabilityState.SUPPORTED)
    global -> AgentCapability(CapabilityState.PARTIAL, CapabilityLimit.GLOBAL_ONLY)
    project -> AgentCapability(CapabilityState.PARTIAL, CapabilityLimit.PROJECT_ONLY)
    else -> AgentCapability(CapabilityState.UNSUPPORTED, CapabilityLimit.NOT_SUPPORTED)
}
