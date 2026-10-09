package dev.ruleblend.core.usecase

import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.McpWrite
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.Target
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Puts library objects into targets.
 *
 * Every write goes through the very services the Place surface writes with, so there is no second
 * write path and the atomicity and managed-region boundaries hold here as they do there. What this
 * adds over those services is the decision: [InstallPolicy] answers once, for all three object
 * kinds, whether a pair may be written, and the answer is reported rather than assumed.
 */
class InstallObject(
    rules: IntegrationService,
    mcp: McpBlockService = McpBlockService.None,
    skills: SkillInstallService? = null,
    policy: InstallPolicy,
    subagents: SubagentInstallService? = null,
) {
    private val writer = ObjectWriter(rules, mcp, skills, subagents, policy)

    operator fun invoke(command: InstallCommand): WriteReport =
        writer.run(command.copy(mode = WriteMode.INSTALL))

    /** The single-pair form, for a facade that acts on one object in one place. */
    operator fun invoke(target: Target, item: InstallItem, force: Boolean = false): WriteReport =
        invoke(InstallCommand(listOf(target), listOf(item), WriteMode.INSTALL, force))
}

/**
 * Takes library objects back out of targets.
 *
 * Refuses the same hand-edited copies an install refuses: the user asked to take the object out, not
 * to throw away that particular edit — discarding it is a separate decision, made on the file.
 */
class RemoveObject(
    rules: IntegrationService,
    mcp: McpBlockService = McpBlockService.None,
    skills: SkillInstallService? = null,
    policy: InstallPolicy,
    subagents: SubagentInstallService? = null,
) {
    private val writer = ObjectWriter(rules, mcp, skills, subagents, policy)

    operator fun invoke(command: InstallCommand): WriteReport =
        writer.run(command.copy(mode = WriteMode.REMOVE))

    operator fun invoke(target: Target, item: InstallItem, force: Boolean = false): WriteReport =
        invoke(InstallCommand(listOf(target), listOf(item), WriteMode.REMOVE, force))
}

/**
 * One pass over targets × objects. A refusal or a failure of one pair never stops the rest: a bulk
 * action that gives up halfway leaves a fleet in a state nobody asked for.
 */
internal class ObjectWriter(
    private val rules: IntegrationService,
    private val mcp: McpBlockService,
    private val skills: SkillInstallService?,
    private val subagents: SubagentInstallService?,
    private val policy: InstallPolicy,
) {

    fun run(command: InstallCommand): WriteReport = WriteReport(
        command.targets.flatMap { target ->
            command.items.map { item -> WriteEntry(target, item, outcome(command, target, item)) }
        },
    )

    private fun outcome(command: InstallCommand, target: Target, item: InstallItem): WriteOutcome {
        return runCatching {
            policy.refusal(item, target, command.mode, command.force)?.let { return@runCatching WriteOutcome.Skipped(it) }
            // A registered project folder that was moved or deleted is not recreated.
            if (target is ProjectTarget && !Files.isDirectory(target.dir)) {
                throw NoSuchFileException(target.dir.toString(), null, "project folder is missing")
            }
            if (item is InstallItem.Mcp && !command.force) {
                // The probe is advisory: re-check ownership under the installer's mutation lock.
                val result = when (command.mode) {
                    WriteMode.INSTALL -> mcp.install(target, item.block, item.group?.let { "g=$it" })
                    WriteMode.REMOVE -> mcp.remove(target, item.block)
                }
                return@runCatching when (result) {
                    McpWrite.DONE -> WriteOutcome.Written
                    McpWrite.FOREIGN, McpWrite.MODIFIED -> WriteOutcome.Skipped(SkipReason.MODIFIED)
                    McpWrite.NOT_APPLICABLE -> if (command.mode == WriteMode.REMOVE) WriteOutcome.Written
                        else WriteOutcome.Skipped(SkipReason.UNSUPPORTED)
                }
            }
            when (command.mode) {
                WriteMode.INSTALL -> write(target, item, command.force)
                WriteMode.REMOVE -> erase(target, item, command.force)
            }
            WriteOutcome.Written
        }.getOrElse { WriteOutcome.Failed(it) }
    }

    /**
     * [force] is passed on rather than assumed: for a rule it rebuilds the whole managed run from the
     * library, discarding hand edits to neighbouring blocks too, so an unforced command must stay
     * unforced here even though the policy already looked at this one region.
     *
     * Only explicitly forced MCP writes reach this method; ordinary writes use the guarded service
     * above so an edit after the policy probe is still protected.
     */
    private fun write(target: Target, item: InstallItem, force: Boolean) {
        when (item) {
            is InstallItem.Rule ->
                rules.install(target, item.block, item.group, force).problem()?.let { throw it }
            is InstallItem.Mcp -> mcp.forceInstall(target, item.block, item.group?.let { "g=$it" })
            is InstallItem.SkillCopy -> requireSkills().install(target, item.skill, overwrite = force, origin = item.group?.let { "g=$it" })
            is InstallItem.Subagent -> requireSubagents().install(target, item.block, overwrite = force, origin = item.group?.let { "g=$it" })
        }
    }

    private fun erase(target: Target, item: InstallItem, force: Boolean) {
        when (item) {
            is InstallItem.Rule -> rules.remove(target, item.block.id, force).problem()?.let { throw it }
            is InstallItem.Mcp -> mcp.forceRemove(target, item.block.id)
            is InstallItem.SkillCopy -> requireSkills().remove(target, item.skill)
            is InstallItem.Subagent -> requireSubagents().remove(target, item.block)
        }
    }

    private fun requireSkills(): SkillInstallService =
        requireNotNull(skills) { "No skill installer is wired in this facade" }

    private fun requireSubagents(): SubagentInstallService =
        requireNotNull(subagents) { "No subagent installer is wired in this facade" }
}
