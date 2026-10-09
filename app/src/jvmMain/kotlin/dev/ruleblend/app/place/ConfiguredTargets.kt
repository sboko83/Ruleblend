package dev.ruleblend.app.place

import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.normalizedKeys
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.projectKeyOf
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import java.nio.file.Path

/** Places the config describes: agent-global files first, then project folders. */
data class ConfiguredTargets(
    val agents: List<AgentGlobalTarget> = emptyList(),
    val projects: List<ProjectTarget> = emptyList(),
) {
    val all: List<Target> get() = agents + projects
}

/**
 * The single reading of "which places exist": the Place surface and the Library usage scan must
 * never disagree about the set of targets or which agents are enabled in each of them.
 */
fun configuredTargets(config: AppConfig, agents: List<AgentAdapter>): ConfiguredTargets {
    val normalized = config.normalizedKeys()
    val available = agents.filter { it.isAvailable() && it.id !in config.hiddenAgents }
    return ConfiguredTargets(
        agents = available.map { AgentGlobalTarget(it) },
        projects = normalized.projects.map { path ->
            val disabled = normalized.disabledAgents[path].orEmpty()
            ProjectTarget(Path.of(path), available.filter { it.id !in disabled })
        },
    )
}

/** Stable identity of a place across surfaces; a Library row uses it to open the Place screen. */
fun placeId(target: Target): String = when (target) {
    is AgentGlobalTarget -> "agent:${target.agent.id}"
    is ProjectTarget -> projectPlaceId(target.dir.projectKey())
}

/**
 * The same identity built from a project key alone. The config stores project keys (set members,
 * rule scopes) while a scan stores place ids, so every surface joining the two goes through here
 * instead of spelling the prefix out again.
 */
fun projectPlaceId(projectKey: String): String = "project:${projectKeyOf(projectKey)}"
