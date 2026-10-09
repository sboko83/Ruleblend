package dev.ruleblend.app.library

import dev.ruleblend.app.place.configuredTargets
import dev.ruleblend.app.place.placeId
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.usecase.InstallCommand
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.InstallObject
import dev.ruleblend.core.usecase.InstallPolicy
import dev.ruleblend.core.usecase.RemoveObject
import dev.ruleblend.mcp.install.McpInstallService

/**
 * Bulk install from the Library. Resolving which places were ticked is all this adds: the writes and
 * every refusal behind them are [InstallObject] and [RemoveObject], the same use cases every other
 * surface writes through, so there is no second write path and no second safety policy.
 *
 * Nothing is forced. An object already edited by hand in a place is reported as skipped instead of
 * overwritten — deciding between the library version and a local edit belongs to that one place.
 */
class LibraryPlaceInstallService(
    private val configStore: ConfigStore,
    private val agents: List<AgentAdapter>,
    service: IntegrationService,
    mcpService: McpInstallService,
    skillService: SkillInstallService,
    subagentService: SubagentInstallService? = null,
) : LibraryPlaceInstaller {

    private val policy = InstallPolicy(service, mcpService, skillService, { configStore.load() }, subagentService)
    private val installObject = InstallObject(service, mcpService, skillService, policy, subagentService)
    private val removeObject = RemoveObject(service, mcpService, skillService, policy, subagentService)

    override fun places(): List<LibraryPlaceRef> =
        configuredTargets(configStore.load(), agents).all.map { target ->
            LibraryPlaceRef(
                id = placeId(target),
                name = target.name,
                kind = if (target is AgentGlobalTarget) LibraryPlaceKind.AGENT else LibraryPlaceKind.PROJECT,
            )
        }

    override fun install(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport =
        installObject(InstallCommand(targets(placeIds), items)).toLibraryReport()

    override fun remove(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport =
        removeObject(InstallCommand(targets(placeIds), items)).toLibraryReport()

    private fun targets(placeIds: Set<String>): List<Target> =
        configuredTargets(configStore.load(), agents).all.filter { placeId(it) in placeIds }
}
