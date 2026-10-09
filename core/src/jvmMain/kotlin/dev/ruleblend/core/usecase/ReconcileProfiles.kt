package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.McpWrite
import dev.ruleblend.core.integration.OrphanRemoval
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.LibraryRepository

/** The kind of portable object whose presence is reconciled by a project profile. */
enum class ProfileObjectKind { RULE, MCP, SKILL, SUBAGENT }

/** One object and the origin that should own its copy after reconciliation. */
data class ProfileReconcileItem(
    val id: String,
    val kind: ProfileObjectKind,
    val origin: String? = null,
)

/** What became of one profile-controlled object. Protected copies are deliberately left in place. */
sealed interface ProfileReconcileOutcome {
    data object Installed : ProfileReconcileOutcome
    data object Removed : ProfileReconcileOutcome
    data object Retagged : ProfileReconcileOutcome
    data object Unchanged : ProfileReconcileOutcome
    data class Skipped(val reason: SkipReason) : ProfileReconcileOutcome
    data class Failed(val error: Throwable) : ProfileReconcileOutcome
}

/** One typed row of [ProfileReconcileReport]. `previousOrigin` is null for a copy that was absent or base-owned. */
data class ProfileReconcileEntry(
    val item: ProfileReconcileItem,
    val previousOrigin: String?,
    val outcome: ProfileReconcileOutcome,
)

/** Complete, typed result of reconciling one project's active profile bindings. */
data class ProfileReconcileReport(
    val entries: List<ProfileReconcileEntry>,
    /** Active bindings whose portable profile is no longer in the library. */
    val missingProfiles: List<String> = emptyList(),
) {
    val installed: Int get() = entries.count { it.outcome is ProfileReconcileOutcome.Installed }
    val removed: Int get() = entries.count { it.outcome is ProfileReconcileOutcome.Removed }
    val retagged: Int get() = entries.count { it.outcome is ProfileReconcileOutcome.Retagged }
    val unchanged: Int get() = entries.count { it.outcome is ProfileReconcileOutcome.Unchanged }
    val failed: Int get() = entries.count { it.outcome is ProfileReconcileOutcome.Failed }
    val skipped: Map<SkipReason, Int>
        get() = entries.mapNotNull { (it.outcome as? ProfileReconcileOutcome.Skipped)?.reason }
            .groupingBy { it }
            .eachCount()
}

/**
 * Makes one project's installed objects equal its base plus every active profile.
 *
 * Base is discovered from the installed origin rather than remembered separately: a copy with no
 * `p=` tag (including an existing group tag) is left in place and wins over any profile that also
 * names it. Multiple profiles need one physical copy, so their binding order chooses its `p=` tag.
 * Every write still goes through the existing install services and [InstallPolicy]; reconciliation
 * never turns switching a profile into permission to overwrite a hand edit. A copy whose library
 * object was deleted is taken out too, decided against the copy the service recorded — see [remove].
 */
class ReconcileProfiles(
    private val repository: LibraryRepository,
    private val configStore: ConfigStore,
    private val rules: IntegrationService,
    private val policy: InstallPolicy,
    private val mcp: McpBlockService = McpBlockService.None,
    private val skills: SkillInstallService? = null,
    private val subagents: SubagentInstallService? = null,
) {

    operator fun invoke(project: ProjectTarget): ProfileReconcileReport {
        val bindings = configStore.load().projectProfiles[project.dir.projectKey()].orEmpty().filter { it.active }
        val profiles = repository.listProfiles().associateBy { it.id }
        val missing = bindings.map { it.id }.filter { it !in profiles }
        val desired = desiredItems(bindings.mapNotNull { profiles[it.id] }, repository.listGroups())
        val current = currentItems(project)
        val all = (desired.keys + current.keys).sortedWith(compareBy<ProfileReconcileKey> { it.kind.ordinal }.thenBy { it.id })
        return ProfileReconcileReport(
            entries = all.map { key -> reconcile(project, key, desired[key], current[key], key in current) },
            missingProfiles = missing.distinct(),
        )
    }

    private fun desiredItems(activeProfiles: List<Profile>, groups: List<Group>): Map<ProfileReconcileKey, ProfileReconcileItem> {
        val blocks = repository.listBlocks().associateBy { it.id }
        val skillsById = repository.listSkills().associateBy { it.id }
        val groupsById = groups.associateBy { it.id }
        return buildMap {
            activeProfiles.forEach { profile ->
                val includedGroups = profile.groupIds.mapNotNull(groupsById::get)
                val blockIds = profile.blockIds + includedGroups.flatMap { it.blockIds } + profile.subagentIds
                blockIds.mapNotNull(blocks::get).forEach { block ->
                    putIfAbsent(ProfileReconcileKey(block.id, block.kind()), ProfileReconcileItem(block.id, block.kind(), "p=${profile.id}"))
                }
                (profile.skillIds + includedGroups.flatMap { it.skillIds }).mapNotNull(skillsById::get).forEach { skill ->
                    putIfAbsent(ProfileReconcileKey(skill.id, ProfileObjectKind.SKILL), ProfileReconcileItem(skill.id, ProfileObjectKind.SKILL, "p=${profile.id}"))
                }
            }
        }
    }

    private fun currentItems(project: ProjectTarget): Map<ProfileReconcileKey, String?> = buildMap {
        rules.installedOrigins(project).forEach { (id, origin) -> put(ProfileReconcileKey(id, ProfileObjectKind.RULE), origin) }
        mcp.installedOrigins(project).forEach { (id, origin) -> put(ProfileReconcileKey(id, ProfileObjectKind.MCP), origin) }
        skills?.installedOrigins(project)?.forEach { (id, origin) -> put(ProfileReconcileKey(id, ProfileObjectKind.SKILL), origin) }
        subagents?.installedOrigins(project)?.forEach { (id, origin) -> put(ProfileReconcileKey(id, ProfileObjectKind.SUBAGENT), origin) }
    }

    private fun reconcile(
        project: ProjectTarget,
        key: ProfileReconcileKey,
        desired: ProfileReconcileItem?,
        currentOrigin: String?,
        currentExists: Boolean,
    ): ProfileReconcileEntry {
        val wanted = when {
            desired == null -> null
            currentExists && currentOrigin?.startsWith("p=") != true -> currentOrigin
            else -> desired.origin
        }
        val item = desired ?: ProfileReconcileItem(key.id, key.kind, origin = null)
        val outcome = when {
            !currentExists && wanted != null -> install(project, item, ProfileReconcileOutcome.Installed)
            currentExists && wanted == null && currentOrigin?.startsWith("p=") == true -> remove(project, item)
            currentExists && wanted == currentOrigin -> ProfileReconcileOutcome.Unchanged
            currentExists && wanted != null -> install(project, item.copy(origin = wanted), ProfileReconcileOutcome.Retagged)
            else -> ProfileReconcileOutcome.Unchanged
        }
        return ProfileReconcileEntry(item.copy(origin = wanted), currentOrigin, outcome)
    }

    /**
     * The one write that both installing and retagging need: an object already in place only differs
     * by the origin its manifest carries, and that is rewritten by writing the object again.
     */
    private fun install(
        project: ProjectTarget,
        item: ProfileReconcileItem,
        outcome: ProfileReconcileOutcome,
    ): ProfileReconcileOutcome = write(project, item, WriteMode.INSTALL) {
        when (item.kind) {
            ProfileObjectKind.RULE -> rules.install(project, block(item), origin = item.origin).problem()?.let { throw it }
            ProfileObjectKind.MCP -> return@write mcp.install(project, block(item), item.origin).outcome(outcome)
            ProfileObjectKind.SKILL -> requireNotNull(skills) { "No skill installer is wired in this facade" }.install(project, skill(item), origin = item.origin)
            ProfileObjectKind.SUBAGENT -> requireNotNull(subagents) { "No subagent installer is wired in this facade" }.install(project, block(item), origin = item.origin)
        }
        outcome
    }

    /**
     * Takes a profile-owned copy out. An object still in the library goes through [InstallPolicy]
     * and the ordinary remove; one that was deleted from the library leaves neither a body for the
     * policy to compare the copy with nor one to hand the installer, so its removal is decided
     * against the copy each service recorded instead. The protection is the same — a hand edit is
     * still reported and left alone — only its yardstick moves from the library to that record.
     */
    private fun remove(project: ProjectTarget, item: ProfileReconcileItem): ProfileReconcileOutcome =
        if (inLibrary(item)) removeManaged(project, item) else removeOrphan(project, item)

    private fun removeManaged(project: ProjectTarget, item: ProfileReconcileItem): ProfileReconcileOutcome = write(project, item, WriteMode.REMOVE) {
        when (item.kind) {
            ProfileObjectKind.RULE -> rules.remove(project, item.id).problem()?.let { throw it }
            ProfileObjectKind.MCP -> return@write mcp.remove(project, block(item)).outcome(ProfileReconcileOutcome.Removed)
            ProfileObjectKind.SKILL -> requireNotNull(skills) { "No skill installer is wired in this facade" }.remove(project, skill(item))
            ProfileObjectKind.SUBAGENT -> requireNotNull(subagents) { "No subagent installer is wired in this facade" }.remove(project, block(item))
        }
        ProfileReconcileOutcome.Removed
    }

    private fun McpWrite.outcome(success: ProfileReconcileOutcome): ProfileReconcileOutcome = when (this) {
        McpWrite.DONE -> success
        McpWrite.FOREIGN, McpWrite.MODIFIED -> ProfileReconcileOutcome.Skipped(SkipReason.MODIFIED)
        McpWrite.NOT_APPLICABLE -> if (success == ProfileReconcileOutcome.Removed) success
            else ProfileReconcileOutcome.Skipped(SkipReason.UNSUPPORTED)
    }

    private fun removeOrphan(project: ProjectTarget, item: ProfileReconcileItem): ProfileReconcileOutcome = runCatching {
        when (item.kind) {
            ProfileObjectKind.RULE -> rules.removeOrphan(project, item.id)
            ProfileObjectKind.MCP -> mcp.removeOrphan(project, item.id)
            ProfileObjectKind.SKILL -> requireNotNull(skills) { "No skill installer is wired in this facade" }.removeOrphan(project, item.id)
            ProfileObjectKind.SUBAGENT -> requireNotNull(subagents) { "No subagent installer is wired in this facade" }.removeOrphan(project, item.id)
        }
    }.fold(
        onSuccess = {
            when (it) {
                OrphanRemoval.REMOVED, OrphanRemoval.ABSENT -> ProfileReconcileOutcome.Removed
                OrphanRemoval.PROTECTED -> ProfileReconcileOutcome.Skipped(SkipReason.MODIFIED)
            }
        },
        onFailure = ProfileReconcileOutcome::Failed,
    )

    private fun inLibrary(item: ProfileReconcileItem): Boolean = when (item.kind) {
        ProfileObjectKind.SKILL -> repository.loadSkill(item.id) != null
        else -> repository.loadBlock(item.id) != null
    }

    private fun write(
        project: ProjectTarget,
        item: ProfileReconcileItem,
        mode: WriteMode,
        action: () -> ProfileReconcileOutcome,
    ): ProfileReconcileOutcome = runCatching {
        policy.refusal(installItem(item), project, mode, force = false)
            ?.let(ProfileReconcileOutcome::Skipped)
            ?: action()
    }.fold(onSuccess = { it }, onFailure = ProfileReconcileOutcome::Failed)

    private fun installItem(item: ProfileReconcileItem): InstallItem = when (item.kind) {
        ProfileObjectKind.RULE -> InstallItem.Rule(block(item))
        ProfileObjectKind.MCP -> InstallItem.Mcp(block(item))
        ProfileObjectKind.SKILL -> InstallItem.SkillCopy(skill(item))
        ProfileObjectKind.SUBAGENT -> InstallItem.Subagent(block(item))
    }

    private fun block(item: ProfileReconcileItem): Block = requireNotNull(repository.loadBlock(item.id)) {
        "Library block '${item.id}' is unavailable for profile reconciliation"
    }

    private fun skill(item: ProfileReconcileItem): Skill = requireNotNull(repository.loadSkill(item.id)) {
        "Library skill '${item.id}' is unavailable for profile reconciliation"
    }

    private data class ProfileReconcileKey(val id: String, val kind: ProfileObjectKind)
    private fun Block.kind(): ProfileObjectKind = when (type) {
        BlockType.RULE -> ProfileObjectKind.RULE
        BlockType.MCP -> ProfileObjectKind.MCP
        BlockType.SUBAGENT -> ProfileObjectKind.SUBAGENT
    }
}
