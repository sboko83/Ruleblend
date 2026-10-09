package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ruleAppliesTo
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.Target

/**
 * The single answer to "may this object be written into this target, and if not, why".
 *
 * Before this class the same three questions — is it in scope, can this target take it at all, was
 * it edited by hand since Ruleblend wrote it — were asked separately by the Place surface, the bulk
 * installer and the MCP facade, and they had already drifted apart on a modified MCP entry. One
 * implementation is the point: a divergence here is a write into the wrong file or a lost user edit.
 */
class InstallPolicy(
    private val rules: IntegrationService,
    private val mcp: McpBlockService = McpBlockService.None,
    private val skills: SkillInstallService? = null,
    private val config: () -> AppConfig,
    private val subagents: SubagentInstallService? = null,
) {

    /**
     * Why [item] must not be written into [target], or null when it may.
     *
     * A status read that throws counts as "unknown, go ahead": the write itself re-reads the file
     * under a lock and refuses there, so a failing probe must not turn into a silent skip.
     */
    fun refusal(item: InstallItem, target: Target, mode: WriteMode, force: Boolean): SkipReason? = when (item) {
        is InstallItem.Rule -> ruleRefusal(item, target, mode, force)
        is InstallItem.Mcp -> mcpRefusal(item, target, force)
        is InstallItem.SkillCopy -> skillRefusal(item, target, force)
        is InstallItem.Subagent -> subagentRefusal(item, target, force)
    }

    /**
     * Whether [target] lies outside the scope [item] is pinned to — the one refusal a facade may ask
     * about before writing, because it decides whether there is anything to do at all. Everything
     * else is answered while the command runs: a status probe is only worth as much as the file it
     * read, so it belongs next to the write.
     */
    fun outOfScope(item: InstallItem, target: Target): Boolean = when (item) {
        is InstallItem.Rule -> !config().ruleAppliesTo(item.block.id, (target as? ProjectTarget)?.dir)
        is InstallItem.Mcp, is InstallItem.SkillCopy, is InstallItem.Subagent -> false
    }

    /**
     * Scope is checked on install only: a rule that landed somewhere before it was pinned must still
     * be removable from there, and taking something out never widens a scope. [force] does not lift
     * it either — overwriting a local edit is a decision about one copy, not about where a rule lives.
     */
    private fun ruleRefusal(item: InstallItem.Rule, target: Target, mode: WriteMode, force: Boolean): SkipReason? {
        if (mode == WriteMode.INSTALL && outOfScope(item, target)) return SkipReason.OUT_OF_SCOPE
        if (force) return null
        val status = runCatching { rules.status(target, item.block) }.getOrNull()
        return if (status == InstallStatus.MODIFIED) SkipReason.MODIFIED else null
    }

    private fun mcpRefusal(item: InstallItem.Mcp, target: Target, force: Boolean): SkipReason? {
        if (!mcp.installable(target, item.block)) return SkipReason.UNSUPPORTED
        if (force) return null
        val status = runCatching { mcp.status(target, item.block) }.getOrNull()
        return if (status?.conflict == true || status?.status == InstallStatus.MODIFIED) SkipReason.MODIFIED else null
    }

    private fun skillRefusal(item: InstallItem.SkillCopy, target: Target, force: Boolean): SkipReason? {
        val installer = skills ?: return SkipReason.UNSUPPORTED
        if (!installer.appliesTo(target)) return SkipReason.UNSUPPORTED
        if (force) return null
        val status = runCatching { installer.status(target, item.skill) }.getOrNull()
        return if (status?.conflict == true || status?.status == InstallStatus.MODIFIED) SkipReason.MODIFIED else null
    }

    private fun subagentRefusal(item: InstallItem.Subagent, target: Target, force: Boolean): SkipReason? {
        val installer = subagents ?: return SkipReason.UNSUPPORTED
        if (!installer.appliesTo(target)) return SkipReason.UNSUPPORTED
        if (force) return null
        val status = runCatching { installer.status(target, item.block) }.getOrNull()
        return if (status?.conflict == true || status?.status == InstallStatus.MODIFIED) SkipReason.MODIFIED else null
    }
}
