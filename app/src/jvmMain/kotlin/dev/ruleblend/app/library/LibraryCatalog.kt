package dev.ruleblend.app.library

import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import java.nio.file.Path

/** The folder name a rule's project scope ends in — what the reader knows the area by, not its path. */
internal fun projectScopeLabel(project: String): String =
    runCatching { Path.of(project).fileName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: project

enum class LibraryObjectKind { RULE, SUBAGENT, SKILL, MCP, GROUP, PROFILE }

data class LibraryObjectKey(val kind: LibraryObjectKind, val id: String)

/** Skills and blocks have separate id namespaces. */
internal fun sourceUpdateId(kind: LibraryObjectKind, id: String): String =
    if (kind == LibraryObjectKind.SUBAGENT) "subagent:$id" else id

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
                    LibrarySourceFilter.LOCAL -> item.key.kind in setOf(LibraryObjectKind.SKILL, LibraryObjectKind.SUBAGENT) && !item.importedFromGit
                    LibrarySourceFilter.GIT -> item.importedFromGit
                    null -> true
                } &&
                (query.isEmpty() || query in item.name.lowercase() || query in item.description.lowercase())
        }.favoritesFirst()
    }

    /** A star outranks the listing order, and nothing else about the order changes. */
    private fun List<LibraryObject>.favoritesFirst(): List<LibraryObject> = sortedByDescending { it.favorite }

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
            LibraryGrouping.TYPE -> LibraryObjectKind.entries.map { kind ->
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
        ): LibraryCatalog {
            val memberships = mutableMapOf<LibraryObjectKey, MutableSet<String>>()
            groups.filter { it.id != ALL_GROUP_ID }.forEach { group ->
                group.blockIds.forEach { id ->
                    val kind = blocks.find { it.id == id }?.type?.objectKind() ?: LibraryObjectKind.RULE
                    memberships.getOrPut(LibraryObjectKey(kind, id), ::mutableSetOf).add(group.id)
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
