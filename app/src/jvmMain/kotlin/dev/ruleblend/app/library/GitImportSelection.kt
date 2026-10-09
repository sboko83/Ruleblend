package dev.ruleblend.app.library

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.GitRepositoryImportPlan

/** Selection is keyed by source path; ambiguous definitions require an explicit assistant. */
internal data class GitImportSelection(
    val skills: Set<String> = emptySet(),
    val assistants: Map<String, String> = emptyMap(),
    val subagents: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = skills.isEmpty() && subagents.isEmpty()
    val selectedSubagents: Map<String, String> get() = assistants.filterKeys { it in subagents }

    fun chooseAssistant(path: String, assistant: String): GitImportSelection =
        copy(assistants = assistants + (path to assistant), subagents = subagents - path)

    fun toggleSubagent(path: String, checked: Boolean): GitImportSelection =
        if (path !in assistants) this else copy(subagents = if (checked) subagents + path else subagents - path)

    companion object {
        fun initial(plan: GitRepositoryImportPlan, skills: List<Skill>, blocks: List<Block>): GitImportSelection {
            val assistants = plan.subagents.mapNotNull { candidate ->
                candidate.sourceAssistant?.let { candidate.path to it }
            }.toMap()
            return GitImportSelection(
                skills = plan.skills.mapNotNull { it.skill.source?.path }.filterNot { path ->
                    skills.any { it.source?.let { source -> source.repository == plan.repository && source.path == path } == true }
                }.toSet(),
                assistants = assistants,
                subagents = assistants.filter { (path, assistant) ->
                    blocks.none { it.source?.let { source ->
                        source.repository == plan.repository && source.path == path && source.assistant == assistant
                    } == true }
                }.keys,
            )
        }
    }
}
