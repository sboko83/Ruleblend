package dev.ruleblend.app.library

import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.model.Skill
import java.nio.file.Path

/** The folder name a rule's project scope ends in — what the reader knows the area by, not its path. */
internal fun projectScopeLabel(project: String): String =
    runCatching { Path.of(project).fileName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: project

enum class LibraryObjectKind { RULE, SUBAGENT, SKILL, MOD, MCP, GROUP, PROFILE }

data class LibraryObjectKey(val kind: LibraryObjectKind, val id: String)

/** Skills and blocks have separate id namespaces. */
internal fun sourceUpdateId(kind: LibraryObjectKind, id: String): String =
    when (kind) {
        LibraryObjectKind.SUBAGENT -> "subagent:$id"
        LibraryObjectKind.MOD -> "mod:$id"
        else -> id
    }

data class LibraryObject(
    val key: LibraryObjectKey,
    val name: String,
    val description: String,
    val version: String,
    val content: String,
    val scope: String? = null,
    /** A rule the user starred in the editor. Starred rules lead every list they appear in. */
    val favorite: Boolean = false,
    /** Common provenance displayed for imported definitions. */
    val gitSource: GitSkillSource? = null,
    /** Upstream revision a local editable fork started from. */
    val forkedFrom: GitSkillSource? = null,
    val groupIds: Set<String> = emptySet(),
    val memberKeys: List<LibraryObjectKey> = emptyList(),
    /**
     * Shipped inside the app rather than held in the library: the workflow skill and Ruleblend's own
     * MCP registration. Readable like everything else, but not the user's to edit, delete or install
     * — Settings connects them, and an app update replaces them.
     */
    val builtIn: Boolean = false,
    val copyText: String = content,
    val sourceAssistant: String? = null,
    val forkAssistant: String? = null,
) {
    val importedFromGit: Boolean get() = gitSource != null
}

/**
 * A version as it reads on screen. A repository that declares no version is imported under the first
 * 12 hex characters of its commit; that is a commit, not a version, so it reads as `@` plus the usual
 * 7-character short hash instead of `v59f4649c44f4`. Library integer versions never take that shape,
 * and a declared version of exactly 12 hex characters with a letter among them does not occur.
 */
fun versionLabel(version: String): String =
    if (COMMIT_VERSION.matches(version) && version.any { it in 'a'..'f' }) "@${version.take(7)}" else "v$version"

private val COMMIT_VERSION = Regex("[0-9a-f]{12}")

/** Ids of the two built-ins. The colon is not a slug character, so no library object can claim them. */
const val BUILTIN_SKILL_ID: String = "builtin:skill"
const val BUILTIN_MCP_ID: String = "builtin:mcp"

enum class LibraryGrouping { GROUP, TYPE, AREA }
enum class LibraryScopeFilter { GLOBAL, PROJECT }
enum class LibrarySourceFilter { LOCAL, GIT }
enum class LibraryUsageFilter { INSTALLED, UNUSED }

data class LibraryFilters(
    val query: String = "",
    val kinds: Set<LibraryObjectKind> = emptySet(),
    val groupId: String? = null,
    val scope: LibraryScopeFilter? = null,
    val source: LibrarySourceFilter? = null,
    val usage: LibraryUsageFilter? = null,
    val updatesAvailable: Boolean = false,
)

data class LibraryCategory(val key: String, val name: String, val items: List<LibraryObject>)

/** A facet the empty-state hint can name; the UI maps each to its localized label. */
enum class LibraryFilterFacet { KIND, GROUP, SCOPE, SOURCE, USAGE, UPDATES, QUERY }

/** One way out of an empty listing: which facet to lift, and the listing lifting it restores. */
data class LibraryFilterRelease(
    val facet: LibraryFilterFacet,
    val kind: LibraryObjectKind? = null,
    val groupId: String? = null,
    val filters: LibraryFilters,
    val restored: Int,
)

/**
 * Whether the Source facet can answer anything about the selected types: it speaks about skills,
 * subagents and mods only. The opening "rules" preset is not a user's choice, so the facet stays
 * visible while it holds; a type picked explicitly takes the filter with it when it cannot speak.
 */
internal fun sourceFacetApplies(kinds: Set<LibraryObjectKind>, typesAreDefault: Boolean): Boolean =
    typesAreDefault || kinds.isEmpty() ||
        kinds.any {
            it == LibraryObjectKind.SKILL || it == LibraryObjectKind.SUBAGENT ||
                it == LibraryObjectKind.MOD && ModsFeature.enabled
        }

/** The one area category that is not a project: everything the library holds for every project. */
const val GLOBAL_SCOPE_CATEGORY: String = "scope:"

/** Immutable Library read-model. Filtering and grouping stay independent from editor drafts. */
class LibraryCatalog private constructor(
    val objects: List<LibraryObject>,
    val groups: List<Group>,
    val profiles: List<Profile>,
) {
    /**
     * [usage] takes part in filtering only once a scan has produced it: before that, "unused" would
     * label the whole library, which is a lie the facet must not tell.
     */
    fun filtered(
        filters: LibraryFilters,
        usage: LibraryUsageIndex = LibraryUsageIndex.EMPTY,
        updateSkillIds: Set<String> = emptySet(),
    ): List<LibraryObject> {
        val query = filters.query.trim().lowercase()
        return objects.filter { item ->
            // A built-in is installed by Settings, not from here: it has no row in the usage index
            // and no Git origin, so it answers neither facet rather than answering them falsely.
            (filters.usage == null && filters.source == null || !item.builtIn) &&
            (
                filters.usage == null || usage.isEmpty ||
                    (usage.isInstalled(item.key) == (filters.usage == LibraryUsageFilter.INSTALLED))
                ) &&
            (!filters.updatesAvailable || sourceUpdateId(item.key.kind, item.key.id) in updateSkillIds && item.importedFromGit) &&
            (filters.kinds.isEmpty() || item.key.kind in filters.kinds) &&
                (filters.groupId == null || filters.groupId in item.groupIds ||
                    item.key.kind == LibraryObjectKind.GROUP && item.key.id == filters.groupId) &&
                when (filters.scope) {
                    LibraryScopeFilter.GLOBAL -> item.scope == null
                    LibraryScopeFilter.PROJECT -> item.scope != null
                    null -> true
                } &&
                when (filters.source) {
                    LibrarySourceFilter.LOCAL -> item.key.kind in setOf(LibraryObjectKind.SKILL, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MOD) && !item.importedFromGit
                    LibrarySourceFilter.GIT -> item.importedFromGit
                    null -> true
                } &&
                (query.isEmpty() || query in item.name.lowercase() || query in item.description.lowercase())
        }.favoritesFirst()
    }

    /** A star outranks the listing order, and nothing else about the order changes. */
    private fun List<LibraryObject>.favoritesFirst(): List<LibraryObject> = sortedByDescending { it.favorite }

    /**
     * The count a facet row shows: the listing that facet's own value produces, every other
     * selected filter kept. A zero seen here is the contradiction that an empty result used to
     * reveal only after the click.
     */
    fun listedCount(
        filters: LibraryFilters,
        usage: LibraryUsageIndex = LibraryUsageIndex.EMPTY,
        updateSkillIds: Set<String> = emptySet(),
    ): Int = filtered(filters, usage, updateSkillIds).size

    /**
     * The one filter whose lifting restores the most objects to an empty listing, or null when no
     * single lift helps — a library with nothing in it, or filters that only clear together. At
     * equal counts a facet wins over the query: dropping it keeps more of what the user typed.
     */
    fun widestRelease(
        filters: LibraryFilters,
        usage: LibraryUsageIndex = LibraryUsageIndex.EMPTY,
        updateSkillIds: Set<String> = emptySet(),
    ): LibraryFilterRelease? {
        data class Lift(val facet: LibraryFilterFacet, val kind: LibraryObjectKind?, val groupId: String?, val next: LibraryFilters)

        val lifts = buildList {
            filters.kinds.forEach { kind -> add(Lift(LibraryFilterFacet.KIND, kind, null, filters.copy(kinds = filters.kinds - kind))) }
            filters.groupId?.let { add(Lift(LibraryFilterFacet.GROUP, null, it, filters.copy(groupId = null))) }
            filters.scope?.let { add(Lift(LibraryFilterFacet.SCOPE, null, null, filters.copy(scope = null))) }
            filters.source?.let { add(Lift(LibraryFilterFacet.SOURCE, null, null, filters.copy(source = null))) }
            filters.usage?.let { add(Lift(LibraryFilterFacet.USAGE, null, null, filters.copy(usage = null))) }
            if (filters.updatesAvailable) add(Lift(LibraryFilterFacet.UPDATES, null, null, filters.copy(updatesAvailable = false)))
            if (filters.query.isNotBlank()) add(Lift(LibraryFilterFacet.QUERY, null, null, filters.copy(query = "")))
        }
        return lifts
            .map { it to filtered(it.next, usage, updateSkillIds).size }
            .filter { (_, restored) -> restored > 0 }
            .maxByOrNull { (_, restored) -> restored }
            ?.let { (lift, restored) -> LibraryFilterRelease(lift.facet, lift.kind, lift.groupId, lift.next, restored) }
    }

    fun categories(
        grouping: LibraryGrouping,
        filters: LibraryFilters,
        usage: LibraryUsageIndex = LibraryUsageIndex.EMPTY,
        updateSkillIds: Set<String> = emptySet(),
    ): List<LibraryCategory> {
        val visible = filtered(filters, usage, updateSkillIds)
        return when (grouping) {
            // The area a rule is pinned to is what tells two project rules apart; by type or by
            // group they all land in one heap named "RULE".
            LibraryGrouping.AREA -> scopeCategories(visible)
            LibraryGrouping.TYPE -> LibraryObjectKind.entries.filter { it != LibraryObjectKind.MOD || ModsFeature.enabled }.map { kind ->
                LibraryCategory("type:${kind.name}", kind.name, visible.filter { it.key.kind == kind })
            }
            LibraryGrouping.GROUP -> buildList {
                groups.filter { it.id != ALL_GROUP_ID }.forEach { group ->
                    add(LibraryCategory("group:${group.id}", group.name, visible.filter { group.id in it.groupIds }))
                }
                add(
                    LibraryCategory(
                        "ungrouped",
                        "UNGROUPED",
                        visible.filter {
                            it.key.kind != LibraryObjectKind.GROUP &&
                            it.key.kind != LibraryObjectKind.MCP &&
                                it.key.kind != LibraryObjectKind.PROFILE &&
                                it.groupIds.isEmpty()
                        },
                    ),
                )
                add(LibraryCategory("subagents", "SUBAGENTS", visible.filter { it.key.kind == LibraryObjectKind.SUBAGENT }))
                add(LibraryCategory("mcp", "MCP", visible.filter { it.key.kind == LibraryObjectKind.MCP }))
                add(LibraryCategory("groups", "GROUPS", visible.filter { it.key.kind == LibraryObjectKind.GROUP }))
                add(LibraryCategory("profiles", "PROFILES", visible.filter { it.key.kind == LibraryObjectKind.PROFILE }))
            }
        }
    }

    /**
     * One category per project area, named by the folder its rules are pinned to and ordered by that
     * name, with everything global last — the bucket goes after the named sections, the way ungrouped
     * does. The full path stays in the key: two checkouts can end in the same folder name, and two
     * categories sharing a key would fold into one section.
     */
    private fun scopeCategories(visible: List<LibraryObject>): List<LibraryCategory> {
        val areas = visible.filter { it.scope != null }
            .groupBy { it.scope.orEmpty() }
            .map { (scope, items) -> LibraryCategory("scope:$scope", projectScopeLabel(scope), items) }
            .sortedBy { it.name.lowercase() }
        val global = visible.filter { it.scope == null }
        return if (global.isEmpty()) areas else areas + LibraryCategory(GLOBAL_SCOPE_CATEGORY, "GLOBAL", global)
    }

    fun count(kind: LibraryObjectKind): Int = objects.count { it.key.kind == kind }

    fun groupCount(groupId: String): Int = objects.count { groupId in it.groupIds }

    companion object {
        fun build(
            blocks: List<Block>,
            groups: List<Group>,
            skills: List<Skill>,
            scopes: Map<String, String>,
            profiles: List<Profile> = emptyList(),
            builtIns: List<LibraryObject> = emptyList(),
            mods: List<Mod> = emptyList(),
        ): LibraryCatalog {
            val memberships = mutableMapOf<LibraryObjectKey, MutableSet<String>>()
            groups.filter { it.id != ALL_GROUP_ID }.forEach { group ->
                group.blockIds.forEach { id ->
                    val kind = blocks.find { it.id == id }?.type?.objectKind() ?: LibraryObjectKind.RULE
                    memberships.getOrPut(LibraryObjectKey(kind, id), ::mutableSetOf).add(group.id)
                }
                if (ModsFeature.enabled) group.modIds.forEach { id ->
                    memberships.getOrPut(LibraryObjectKey(LibraryObjectKind.MOD, id), ::mutableSetOf).add(group.id)
                }
                group.skillIds.forEach { id ->
                    memberships.getOrPut(LibraryObjectKey(LibraryObjectKind.SKILL, id), ::mutableSetOf).add(group.id)
                }
            }
            val items = buildList {
                blocks.forEach { block ->
                    val kind = block.type.objectKind()
                    val key = LibraryObjectKey(kind, block.id)
                    add(
                        LibraryObject(
                            key = key,
                            name = block.name,
                            description = block.description,
                            version = block.version.toString(),
                            content = block.content,
                            copyText = regionFor(block).content,
                            scope = if (block.type == BlockType.RULE) scopes[block.id] else null,
                            favorite = block.favorite,
                            gitSource = block.source?.let { GitSkillSource(it.repository, it.revision, it.path) },
                            forkedFrom = block.forkedFrom?.let { GitSkillSource(it.repository, it.revision, it.path) },
                            sourceAssistant = block.source?.assistant,
                            forkAssistant = block.forkedFrom?.assistant,
                            groupIds = memberships[key].orEmpty(),
                        ),
                    )
                }
                skills.forEach { skill ->
                    val key = LibraryObjectKey(LibraryObjectKind.SKILL, skill.id)
                    add(
                        LibraryObject(
                            key = key,
                            name = skill.name,
                            description = skill.description,
                            version = skill.version,
                            content = skill.content,
                            gitSource = skill.source,
                            forkedFrom = skill.forkedFrom,
                            groupIds = memberships[key].orEmpty(),
                        ),
                    )
                }
                if (ModsFeature.enabled) mods.forEach { mod ->
                    val key = LibraryObjectKey(LibraryObjectKind.MOD, mod.id)
                    add(LibraryObject(key, mod.name, mod.description, mod.version.orEmpty(), "",
                        gitSource = mod.source?.let { GitSkillSource(it.repository, it.revision, it.path) },
                        groupIds = memberships[key].orEmpty(), sourceAssistant = "claude-code"))
                }
                groups.forEach { group ->
                    val key = LibraryObjectKey(LibraryObjectKind.GROUP, group.id)
                    val memberKeys = buildList {
                        group.blockIds.forEach { id ->
                            add(
                                LibraryObjectKey(
                                    blocks.find { it.id == id }?.type?.objectKind() ?: LibraryObjectKind.RULE,
                                    id,
                                ),
                            )
                        }
                        group.skillIds.forEach { add(LibraryObjectKey(LibraryObjectKind.SKILL, it)) }
                        if (ModsFeature.enabled) group.modIds.forEach { add(LibraryObjectKey(LibraryObjectKind.MOD, it)) }
                    }
                    add(
                        LibraryObject(
                            key = key,
                            name = group.name,
                            description = group.description,
                            version = group.version.toString(),
                            content = "",
                            memberKeys = memberKeys,
                        ),
                    )
                }
                profiles.forEach { profile ->
                    val memberKeys = buildList {
                        profile.blockIds.forEach { id ->
                            add(
                                LibraryObjectKey(
                                    blocks.find { it.id == id }?.type?.objectKind() ?: LibraryObjectKind.RULE,
                                    id,
                                ),
                            )
                        }
                        profile.skillIds.forEach { add(LibraryObjectKey(LibraryObjectKind.SKILL, it)) }
                        profile.subagentIds.forEach { add(LibraryObjectKey(LibraryObjectKind.SUBAGENT, it)) }
                        profile.groupIds.forEach { add(LibraryObjectKey(LibraryObjectKind.GROUP, it)) }
                    }
                    add(
                        LibraryObject(
                            key = LibraryObjectKey(LibraryObjectKind.PROFILE, profile.id),
                            name = profile.name,
                            description = profile.description,
                            version = profile.version.toString(),
                            content = "",
                            memberKeys = memberKeys,
                        ),
                    )
                }
            }
            return LibraryCatalog(items + builtIns, groups, profiles)
        }
    }
}

internal fun BlockType.objectKind(): LibraryObjectKind = when (this) {
    BlockType.RULE -> LibraryObjectKind.RULE
    BlockType.SUBAGENT -> LibraryObjectKind.SUBAGENT
    BlockType.MCP -> LibraryObjectKind.MCP
}
