package dev.ruleblend.app.assistance

import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.app.util.probeCli
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import dev.ruleblend.mcp.AgentIntegrationState
import dev.ruleblend.mcp.McpRegistration
import dev.ruleblend.mcp.skill.SkillStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata of a saved Library object; its body remains available through the existing MCP. */
data class AssistanceObject(val kind: Kind, val id: String, val name: String) {
    enum class Kind(val english: String, val russian: String) {
        RULE("rule", "правило"),
        MCP("MCP server", "MCP-сервер"),
        SUBAGENT("subagent", "субагент"),
        SKILL("skill", "скилл"),
    }

    init {
        require(id.isNotBlank() && id.none(Char::isISOControl)) { "A saved object ID is required" }
        require(name.isNotBlank() && name.none(Char::isISOControl)) { "A single-line object name is required" }
    }

    fun draft(language: Language): String = when (language) {
        Language.EN -> "Let's discuss the ${kind.english} $name (ID: $id). " +
            "Use Ruleblend MCP and the ruleblend skill. Let's discuss the task first."
        Language.RU -> "Обсудим ${kind.russian} $name (ID: $id). " +
            "Используй Ruleblend MCP и skill ruleblend. Сначала обсудим задачу."
    }

    companion object {
        fun fromSaved(block: Block): AssistanceObject = AssistanceObject(
            when (block.type) {
                BlockType.RULE -> Kind.RULE
                BlockType.MCP -> Kind.MCP
                BlockType.SUBAGENT -> Kind.SUBAGENT
            },
            block.id, block.name,
        )

        fun fromSaved(skill: Skill): AssistanceObject = AssistanceObject(Kind.SKILL, skill.id, skill.name)
    }
}

data class ReadyAssistanceAssistant(val id: String, val name: String, val cli: CliProbe)

/** Read on demand before offering a launch; visibility and external-terminal arguments do not apply. */
suspend fun discoverAssistanceAssistants(
    agents: List<AgentAdapter>,
    integrationStates: () -> List<AgentIntegrationState>,
    cliProbe: (String) -> CliProbe? = ::probeCli,
): List<ReadyAssistanceAssistant> = withContext(Dispatchers.IO) {
    val states = integrationStates().associateBy { it.agentId }
    agents.mapNotNull { agent ->
        if (agent.id != "claude-code") return@mapNotNull null
        val state = states[agent.id] ?: return@mapNotNull null
        if (state.mcp?.registration != McpRegistration.REGISTERED ||
            state.skill?.status != SkillStatus.INSTALLED) return@mapNotNull null
        val executable = agent.cli?.executable ?: return@mapNotNull null
        val probe = runCatching { cliProbe(executable) }.getOrNull() ?: return@mapNotNull null
        ReadyAssistanceAssistant(agent.id, agent.name, probe)
    }
}

sealed interface AssistanceSelection {
    data object Configure : AssistanceSelection
    data class Launch(val assistant: ReadyAssistanceAssistant) : AssistanceSelection
    data class Choose(val assistants: List<ReadyAssistanceAssistant>) : AssistanceSelection
}

/** No implicit default or substitution when a selected assistant becomes unavailable. */
fun selectAssistanceAssistant(
    selectedIds: Collection<String>,
    ready: List<ReadyAssistanceAssistant>,
): AssistanceSelection {
    val selected = ready.filter { it.id in selectedIds }.distinctBy { it.id }
    return when (selected.size) {
        0 -> AssistanceSelection.Configure
        1 -> AssistanceSelection.Launch(selected.single())
        else -> AssistanceSelection.Choose(selected)
    }
}
