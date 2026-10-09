package dev.ruleblend.app.library

import dev.ruleblend.app.place.configuredTargets
import dev.ruleblend.app.place.placeId
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.FileScan
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.statusOf
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import dev.ruleblend.mcp.install.McpInstallService
import java.nio.file.Path

/**
 * Reads every configured place once and reports what of the library sits in it, together with the
 * state of its files. Rules are resolved from the managed regions of each file — one read per file
 * instead of one per rule and place, the difference between a usable scan and 304 × 30 file reads.
 *
 * The file state (ownership mode, hand-written lines left, drift) comes out of the same read, so
 * Home's attention feed costs the scan nothing beyond what Library already pays.
 */
class LibraryUsageScanner(
    private val configStore: ConfigStore,
    private val agents: List<AgentAdapter>,
    private val service: IntegrationService,
    private val mcpService: McpInstallService,
    private val skillService: SkillInstallService,
    private val subagentService: SubagentInstallService? = null,
) : LibraryUsageSource {

    override fun scan(blocks: List<Block>, skills: List<Skill>): List<LibraryPlaceUsage> {
        val targets = configuredTargets(configStore.load(), agents)
        val rules = blocks.filter { it.type == BlockType.RULE }
        val mcpBlocks = blocks.filter { it.type == BlockType.MCP }
        val subagents = blocks.filter { it.type == BlockType.SUBAGENT }
        val ruleById = rules.associateBy { it.id }
        return targets.all.map { target ->
            // One read per owned file, reused by both halves below. Conflicts are classified from that
            // same read against the library already in memory, so Resolve costs the scan no extra reads.
            val scans = target.ownedFiles().associateWith { file ->
                runCatching { service.scanFile(file, ruleById::get) }
                    .getOrDefault(FileScan(file, exists = false))
            }
            LibraryPlaceUsage(
                id = placeId(target),
                name = target.name,
                kind = if (target is AgentGlobalTarget) LibraryPlaceKind.AGENT else LibraryPlaceKind.PROJECT,
                installs = ruleInstalls(target, rules, scans) +
                    mcpInstalls(target, mcpBlocks) +
                    skillInstalls(target, skills) +
                    subagentInstalls(target, subagents),
                files = placeFiles(target, scans),
                supportsMcp = mcpService.appliesTo(target),
                supportsSkills = skillService.appliesTo(target),
                supportsSubagents = subagentService?.appliesTo(target) == true,
            )
        }
    }

    /**
     * File rows for the attention feed. Pointer files are kept — a hand-written `CLAUDE.md` is
     * exactly what adoption exists for — but a file whose only hand-written line is the `@import`
     * it exists to carry reports nothing to adopt.
     */
    private fun placeFiles(target: Target, scans: Map<Path, FileScan>): List<PlaceFile> {
        val pointers = target.redirects().associate { it.from to it.to }
        return scans.map { (path, scan) ->
            val pointer = service.isPurePointer(scan.unmanaged, pointers[path])
            PlaceFile(
                path = path,
                name = path.fileName?.toString() ?: path.toString(),
                exists = scan.exists,
                mode = scan.mode,
                unmanagedLines = if (pointer) 0 else scan.unmanaged.lineCount,
                drift = scan.drift,
                isPointer = pointer,
                conflicts = scan.conflicts,
            )
        }
    }

    private fun ruleInstalls(
        target: Target,
        rules: List<Block>,
        scans: Map<Path, FileScan>,
    ): Map<LibraryObjectKey, LibraryInstall> {
        val files = target.files()
        if (files.isEmpty()) return emptyMap()
        val perFile = files.map { file -> scans[file]?.regionsById.orEmpty() }
        return rules.mapNotNull { block ->
            val statuses = perFile.mapNotNull { regions -> regions[block.id]?.let { statusOf(it, block) } }
            if (statuses.isEmpty()) return@mapNotNull null
            // Present in some files only: the missing ones still have to be written, which is what
            // "update available" means everywhere else in the app.
            val status = if (statuses.size < files.size) {
                InstallStatus.UPDATE_AVAILABLE
            } else {
                statuses.maxBy { it.rank() }
            }
            LibraryObjectKey(LibraryObjectKind.RULE, block.id) to LibraryInstall(status)
        }.toMap()
    }

    private fun mcpInstalls(target: Target, blocks: List<Block>): Map<LibraryObjectKey, LibraryInstall> =
        blocks.mapNotNull { block ->
            val status = runCatching { mcpService.status(target, block) }.getOrNull() ?: return@mapNotNull null
            LibraryObjectKey(LibraryObjectKind.MCP, block.id) to LibraryInstall(status.status, status.conflict)
        }.toMap()

    private fun skillInstalls(target: Target, skills: List<Skill>): Map<LibraryObjectKey, LibraryInstall> =
        skills.mapNotNull { skill ->
            val status = runCatching { skillService.status(target, skill) }.getOrNull() ?: return@mapNotNull null
            LibraryObjectKey(LibraryObjectKind.SKILL, skill.id) to LibraryInstall(status.status, status.conflict)
        }.toMap()

    private fun subagentInstalls(target: Target, subagents: List<Block>): Map<LibraryObjectKey, LibraryInstall> =
        subagents.mapNotNull { subagent ->
            val status = runCatching { subagentService?.status(target, subagent) }.getOrNull() ?: return@mapNotNull null
            LibraryObjectKey(LibraryObjectKind.SUBAGENT, subagent.id) to LibraryInstall(status.status, status.conflict)
        }.toMap()
}

private fun InstallStatus.rank(): Int = when (this) {
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}
