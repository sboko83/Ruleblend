package dev.ruleblend.app.place

import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.mark
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.core.integration.FileSegment
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.McpFileEntry
import dev.ruleblend.core.integration.PlaceEntry
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.Redirect
import dev.ruleblend.core.integration.SkillDirEntry
import dev.ruleblend.core.integration.SubagentFileEntry
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.TargetOwnershipMode
import java.nio.file.Path

/** What a file tab of a place holds. Ownership is a property of the rules file, not of the place. */
enum class PlaceFileKind {
    /** The instruction file blocks are installed into (`AGENTS.md`, an agent's global file). */
    RULES,

    /** A pointer file whose managed region only imports the rules file (`CLAUDE.md`). */
    POINTER,

    /** An agent's MCP config (`.mcp.json`, `~/.claude.json`). */
    MCP,

    /** A skills directory, one subdirectory per installed skill. */
    SKILLS,

    /** A subagents directory, one file per installed subagent. */
    SUBAGENTS,
}

/**
 * What a place holds, named the way the library names it. A tab is a kind of object, because that is
 * the question the reader arrives with — "what does this project have"; the file it is written to is
 * an address, and an address only matters while installing or while tracing an edit, so it lives one
 * level down, in the sections of a tab.
 */
enum class PlaceTabKind { RULES, SKILLS, SUBAGENTS, MCP }

/** One tab of a place: a kind of object, and every address in this place that can hold it. */
data class PlaceTab(val kind: PlaceTabKind, val sections: List<PlaceFileTab>)

/** Home opens the first changed kind in display order; ordinary navigation starts at the first tab. */
internal fun initialPlaceTab(
    tabs: List<PlaceTab>,
    focusChanges: Boolean,
    marks: (PlaceFileTab) -> Set<StatusMark>,
): PlaceTabKind? {
    val changed = if (focusChanges) tabs.firstOrNull { tab ->
        tab.sections.any { file ->
            marks(file).any { it == StatusMark.UPDATE || it == StatusMark.CONFLICT }
        }
    } else null
    return changed?.kind ?: tabs.firstOrNull()?.kind
}

/** The kind a file belongs under. A pointer is not a kind of its own — it is how rules are reached. */
fun PlaceFileKind.tabKind(): PlaceTabKind = when (this) {
    PlaceFileKind.RULES, PlaceFileKind.POINTER -> PlaceTabKind.RULES
    PlaceFileKind.MCP -> PlaceTabKind.MCP
    PlaceFileKind.SKILLS -> PlaceTabKind.SKILLS
    PlaceFileKind.SUBAGENTS -> PlaceTabKind.SUBAGENTS
}

/**
 * The tabs of a place, one per kind, in reading order: what the agent is told, then what it can run
 * — skills, then subagents — then what it can connect to. A kind with no address at all is left out
 * — an agent that cannot hold skills is not offered an empty skills tab; a kind that has an address
 * but nothing in it stays, because "nothing installed here" is an answer and the tab is where it is
 * given.
 */
fun placeTabs(files: List<PlaceFileTab>): List<PlaceTab> =
    PlaceTabKind.entries.mapNotNull { kind ->
        files.filter { it.kind.tabKind() == kind }.takeIf { it.isNotEmpty() }?.let { PlaceTab(kind, it) }
    }

/**
 * One file tab of the selected place. [title] is the path as shown under the place's root, so two
 * agents with different config files stay distinguishable. [mode] is only carried by [PlaceFileKind.RULES]:
 * ownership belongs to a file, and the tab is where the file is.
 */
data class PlaceFileTab(
    val key: String,
    val title: String,
    val kind: PlaceFileKind,
    val path: Path,
    val mode: TargetOwnershipMode? = null,
    /** Name of the file a pointer imports, e.g. `AGENTS.md`. */
    val importedName: String? = null,
    val exists: Boolean = true,
)

/** One installed object inside a managed run, as the file holds it. */
data class PlaceBlockRow(
    val id: String,
    /** Library name when the object is still in the library; the raw id otherwise. */
    val name: String,
    /** Version as the file or the library states it: rules count up, skills carry their own string. */
    val version: String?,
    val group: String?,
    /** `null` when the object is installed but no longer in the library — nothing to compare against. */
    val status: InstallStatus?,
    /**
     * The block's text as this file holds it, so the row can be unfolded into what it actually
     * installed. `null` for rows the file does not spell out: an MCP entry or a skill directory is
     * listed from its manifest, not read out of the preview.
     */
    val content: String? = null,
    /**
     * The text the library would install for this object, so a drifted row can show what exactly was
     * edited rather than only that something was. `null` when there is nothing to compare against.
     */
    val libraryContent: String? = null,
    /** Whether Ruleblend owns this address, or the directory was only found on disk. */
    val origin: PlaceEntryOrigin = PlaceEntryOrigin.MANAGED,
    /** Physical address for a directory or config entry; markdown regions stay in their file part. */
    val path: Path? = null,
    /** Agent which owns a config entry when one place covers several agents. */
    val agentId: String? = null,
    /** One-line disk metadata, for example a skill's frontmatter description. */
    val subtitle: String? = null,
    /** Stable physical-entry key used for local hide/show choices. */
    val entryKey: String? = null,
    /** A bundled Ruleblend artifact, maintained through Settings instead of this Place view. */
    val managedByRuleblend: Boolean = false,
)

/** A file in preview order: the user's own text, and the regions Ruleblend owns. */
sealed interface PlaceFilePart {
    /** Hand-written text — shown muted, because it is not Ruleblend's to change. */
    data class Hand(val text: String) : PlaceFilePart

    /** A Ruleblend-managed run — highlighted, one row per installed object. */
    data class Managed(
        val rows: List<PlaceBlockRow>,
        /** Disk entries which this step collects but leaves for the foreign-entry UI in step 4. */
        val additionalRows: List<PlaceBlockRow> = emptyList(),
    ) : PlaceFilePart

    /** A managed run with no blocks in it: a pointer's import line, shown as the text it is. */
    data class ManagedText(val text: String) : PlaceFilePart

    /**
     * The bare import line of a pointer file. It is written by Ruleblend but not owned by it — no
     * markers, nothing to overwrite — so it is neither a managed run nor the user's own prose: it is
     * the link itself, and the only thing this file is for.
     */
    data class Pointer(val text: String) : PlaceFilePart
}

/**
 * File tabs of [target] in reading order: the rules files first, then the pointers that lead to
 * them, then the MCP configs and skill directories the same place also owns. One place, one row of
 * tabs — this is the collapse the architecture already performs, made visible.
 */
fun placeFileTabs(
    target: Target,
    ownership: (Path) -> TargetOwnershipMode,
    exists: (Path) -> Boolean,
    mcpFiles: List<Path> = emptyList(),
    skillDirectories: List<Path> = emptyList(),
    subagentDirectories: List<Path> = emptyList(),
): List<PlaceFileTab> {
    val root = target.importRoot()
    fun title(path: Path): String = displayPath(root, path)

    val rules = target.files().map { path ->
        PlaceFileTab(
            key = path.toString(),
            title = title(path),
            kind = PlaceFileKind.RULES,
            path = path,
            mode = ownership(path),
            exists = exists(path),
        )
    }
    val pointers = target.redirects().map { redirect: Redirect ->
        PlaceFileTab(
            key = redirect.from.toString(),
            title = title(redirect.from),
            kind = PlaceFileKind.POINTER,
            path = redirect.from,
            importedName = redirect.to.fileName?.toString() ?: redirect.to.toString(),
            exists = exists(redirect.from),
        )
    }
    val mcp = mcpFiles.map { path ->
        PlaceFileTab(
            key = path.toString(),
            title = title(path),
            kind = PlaceFileKind.MCP,
            path = path,
            exists = exists(path),
        )
    }
    val skills = skillDirectories.map { path ->
        PlaceFileTab(
            key = path.toString(),
            title = title(path) + path.fileSystem.separator,
            kind = PlaceFileKind.SKILLS,
            path = path,
            exists = exists(path),
        )
    }
    val subagents = subagentDirectories.map { path ->
        PlaceFileTab(
            key = path.toString(),
            title = title(path) + path.fileSystem.separator,
            kind = PlaceFileKind.SUBAGENTS,
            path = path,
            exists = exists(path),
        )
    }
    return rules + pointers + mcp + skills + subagents
}

/**
 * The preview of one markdown file: [segments] as core read them, with library names and statuses
 * attached. Order is the file's own — a managed run does not always come last.
 */
fun placeFileParts(
    segments: List<FileSegment>,
    name: (String) -> String? = { null },
    status: (String) -> InstallStatus? = { null },
    libraryContent: (String) -> String? = { null },
): List<PlaceFilePart> = segments.map { segment ->
    when (segment) {
        is FileSegment.Hand -> PlaceFilePart.Hand(segment.text)
        is FileSegment.Run -> PlaceFilePart.Managed(
            segment.regions.map { region ->
                PlaceBlockRow(
                    id = region.id,
                    name = name(region.id) ?: region.id,
                    version = region.version.toString(),
                    group = region.group,
                    status = status(region.id),
                    content = region.content,
                    libraryContent = libraryContent(region.id),
                )
            },
        )
    }
}

/**
 * The preview of a pointer file. Its import is one `@import` line rather than blocks, so it is shown
 * as the text the agent reads — a row with a version badge would claim a rule that is not there.
 *
 * [importLine] is what this pointer imports (`@AGENTS.md`). A hand segment that is nothing but that
 * line is the pointer doing its job, not prose somebody wrote: it reads as the link. A run is a
 * pointer written by an earlier version, still readable until the next sync converts it.
 */
fun placePointerParts(segments: List<FileSegment>, importLine: String? = null): List<PlaceFilePart> =
    segments.mapNotNull { segment ->
        when (segment) {
            is FileSegment.Hand -> {
                val lines = segment.text.lines().mapNotNull { it.trim().takeIf(String::isNotBlank) }
                when {
                    importLine != null && lines == listOf(importLine) -> PlaceFilePart.Pointer(importLine)
                    // The line beside other text: the prose stays the user's, the link is its own part.
                    importLine != null && importLine in lines -> PlaceFilePart.Hand(
                        segment.text.lines().filterNot { it.trim() == importLine }.joinToString("\n"),
                    )
                    else -> PlaceFilePart.Hand(segment.text)
                }
            }
            is FileSegment.Run -> PlaceFilePart.ManagedText(segment.regions.joinToString("\n\n") { it.content })
        }
    }

/**
 * Rows of the MCP and skills tabs: what Ruleblend has installed there, by [statuses]. Entries the
 * user put in those files themselves are not listed — the status services only read our own.
 *
 * [content] and [libraryContent] are what the config file holds for an entry and what the library
 * would write there. A tab that can supply them unfolds its rows the way a rules file does; one that
 * cannot — a skill is a directory, not a text — leaves them out and keeps rows as single lines.
 */
fun installedRows(
    items: List<Triple<String, String, String>>,
    statuses: Map<String, InstallStatus>,
    content: (String) -> String? = { null },
    libraryContent: (String) -> String? = { null },
): List<PlaceBlockRow> =
    items.mapNotNull { (id, name, version) ->
        statuses[id]?.let {
            PlaceBlockRow(
                id = id,
                name = name,
                version = version,
                group = null,
                status = it,
                content = content(id),
                libraryContent = libraryContent(id),
            )
        }
    }

/**
 * Joins the legacy library/status rows with entries discovered at the physical skill roots.
 *
 * A sidecar entry is the authority for ownership: an on-disk skill that happens to share a library
 * id remains foreign without it. One physical entry is kept for each [PlaceEntry.key], an installed
 * object no entry accounts for keeps its own row, and the result is grouped in the order the tab
 * shows: ours, orphaned copies, foreign copies. Supplying no disk entries leaves the rows exactly as
 * they were, which preserves the legacy view for a caller without disk enumeration.
 */
data class PlaceDiskEntry(
    val name: String,
    val version: String?,
    val path: Path,
    val subtitle: String?,
    val text: String?,
    val agentId: String? = null,
    val managedByRuleblend: Boolean = false,
)

fun <T> placeEntries(
    managedRows: List<PlaceBlockRow>,
    entries: List<PlaceEntry<T, String>>,
    diskEntry: (T) -> PlaceDiskEntry,
): List<PlaceBlockRow> {
    val managedById = managedRows.associateBy(PlaceBlockRow::id)
    val physical = entries
        .distinctBy { it.key }
        .map { entry ->
            val disk = diskEntry(entry.value)
            val managed = if (entry.origin == PlaceEntryOrigin.MANAGED) managedById[entry.libraryId] else null
            managed?.copy(
                // Discovery has already read the native definition. Keep that text when a foreign
                // entry becomes managed: skills and subagents have no separate installed-text
                // cache, and without it their row cannot be opened in the Place preview.
                content = managed.content ?: disk.text,
                origin = entry.origin,
                path = disk.path,
                agentId = disk.agentId,
                subtitle = disk.subtitle,
                entryKey = entry.key,
                managedByRuleblend = disk.managedByRuleblend,
            ) ?: PlaceBlockRow(
                id = entry.libraryId,
                name = disk.name,
                version = disk.version,
                group = null,
                status = null,
                content = disk.text,
                origin = entry.origin,
                path = disk.path,
                agentId = disk.agentId,
                subtitle = disk.subtitle,
                entryKey = entry.key,
                managedByRuleblend = disk.managedByRuleblend,
            )
        }
    // An installed object with nothing at this address keeps its row: the tab is where its status is
    // read, and dropping it would answer "nothing installed" for a copy that is merely missing. An
    // address a foreign entry already holds is that entry's row alone: without a record the copy is
    // not ours, and a second row for the library object would show one file twice.
    val claimed = entries.filter { it.origin == PlaceEntryOrigin.MANAGED }.mapTo(mutableSetOf()) { it.libraryId }
    val foreign = entries.filter { it.origin == PlaceEntryOrigin.FOREIGN || it.origin == PlaceEntryOrigin.IGNORED }
        .mapTo(mutableSetOf()) { it.libraryId }
    return (physical + managedRows.filter { it.id !in claimed && it.id !in foreign })
        .sortedBy { row ->
            when (row.origin) {
                PlaceEntryOrigin.MANAGED -> 0
                PlaceEntryOrigin.ORPHAN -> 1
                PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED -> 2
            }
        }
}

fun placeEntries(
    managedRows: List<PlaceBlockRow>,
    entries: List<PlaceEntry<SkillDirEntry, String>>,
): List<PlaceBlockRow> = placeEntries(managedRows, entries) { skill ->
    PlaceDiskEntry(
        name = requireNotNull(skill.meta.name),
        version = skill.meta.version,
        path = skill.path,
        subtitle = skill.meta.description?.takeIf(String::isNotBlank),
        text = skill.text.takeIf(String::isNotBlank),
        managedByRuleblend = dev.ruleblend.mcp.skill.BundledSkill.isOwned(skill.text),
    )
}

/**
 * The entries physically kept in [directory]. A place can read several skill roots — an agent may
 * have a native and a shared one — and each is its own tab, so an entry belongs to the tab of the
 * directory it sits in and to no other.
 */
fun skillEntriesIn(
    entries: List<PlaceEntry<SkillDirEntry, String>>,
    directory: Path,
): List<PlaceEntry<SkillDirEntry, String>> {
    val root = directory.normalize()
    return entries.filter { it.value.path.parent?.normalize() == root }
}

/** The entries physically kept in one subagent definition directory. */
fun subagentEntriesIn(
    entries: List<PlaceEntry<SubagentFileEntry, String>>,
    directory: Path,
): List<PlaceEntry<SubagentFileEntry, String>> {
    val root = directory.normalize()
    return entries.filter { it.value.path.parent?.normalize() == root }
}

/** The entries physically kept in one MCP config file. */
fun mcpEntriesIn(
    entries: List<PlaceEntry<McpFileEntry, String>>,
    file: Path,
): List<PlaceEntry<McpFileEntry, String>> {
    val normalized = file.normalize()
    return entries.filter { it.value.file.normalize() == normalized }
}

/** What tells two foreign rows apart when they fold: two MCP entries share one config file. */
internal fun PlaceBlockRow.foldId(): String = entryKey ?: path?.toString() ?: id

/** Object counts separated by Ruleblend-owned and visible foreign disk entries. */
data class PlaceObjectCount(val ours: Int, val foreign: Int) {
    val total: Int get() = ours + foreign
}

/**
 * How many objects a file holds, separated for the tab's `ours · foreign` label. The count says what
 * the tab shows, so a hidden entry stays out of it while an orphaned copy and a bundled artifact
 * join `ours`: Ruleblend installed them and their rows are on the tab, even without a Library object.
 */
fun placeObjectCount(parts: List<PlaceFilePart>): PlaceObjectCount {
    val rows = parts.filterIsInstance<PlaceFilePart.Managed>().flatMap { it.rows + it.additionalRows }
    return PlaceObjectCount(
        ours = rows.count {
            it.managedByRuleblend || it.origin == PlaceEntryOrigin.MANAGED || it.origin == PlaceEntryOrigin.ORPHAN
        },
        foreign = rows.count { it.origin == PlaceEntryOrigin.FOREIGN && !it.managedByRuleblend },
    )
}

/** How many entries of a file the local hide switch is currently keeping out of sight. */
fun placeHiddenCount(parts: List<PlaceFilePart>): Int =
    parts.filterIsInstance<PlaceFilePart.Managed>()
        .sumOf { part -> (part.rows + part.additionalRows).count { it.origin == PlaceEntryOrigin.IGNORED } }

/**
 * Whether a file has anything to show: text of its own, or an object installed into it. A file that
 * has neither is not drawn as an empty block — it is named in one line at the end of the tab, so its
 * address stays visible without taking a screenful to say nothing.
 */
fun placeSectionHasContent(file: PlaceFileTab, parts: List<PlaceFilePart>): Boolean =
    file.exists && parts.any { part ->
        when (part) {
            is PlaceFilePart.Hand -> part.text.isNotBlank()
            is PlaceFilePart.ManagedText -> part.text.isNotBlank()
            is PlaceFilePart.Pointer -> part.text.isNotBlank()
            is PlaceFilePart.Managed -> part.rows.isNotEmpty() || part.additionalRows.isNotEmpty()
        }
    }

/**
 * What one file's dot says: one mark per object it holds, plus [StatusMark.UNMANAGED] when the file
 * is on disk and Ruleblend has nothing of its own in it. The same marks the place's dot in the list
 * sums up, so the colour of that dot can be traced to the file that earned it. Empty for a file that
 * does not exist yet — an address with nothing at it has no state to report.
 *
 * A managed run with no objects in it is not a region: an empty one is what an address with nothing
 * installed is shown as, and a skills directory full of foreign entries carries one too. Counting it
 * as managed left the tab with no dot at all, which reads as "no state" rather than "nothing of ours".
 */
fun placeFileMarks(file: PlaceFileTab, parts: List<PlaceFilePart>): Set<StatusMark> {
    if (!file.exists) return emptySet()
    val managed = parts.filterIsInstance<PlaceFilePart.Managed>()
    val marks = managed.flatMap { it.rows }.mapNotNullTo(mutableSetOf()) { row -> row.status?.mark() }
    // A pointer counts as answered for: the file is doing exactly what Ruleblend put it there for,
    // and calling it unmanaged would light the place up over a line that is already right.
    val hasBuiltIn = managed.any { part -> (part.rows + part.additionalRows).any { it.managedByRuleblend } }
    if (hasBuiltIn) marks += StatusMark.MANAGED
    val hasRegion = managed.any { it.rows.isNotEmpty() } || hasBuiltIn ||
        parts.any { it is PlaceFilePart.ManagedText || it is PlaceFilePart.Pointer }
    if (!hasRegion) marks += StatusMark.UNMANAGED
    return marks
}

/** [path] as shown under [root], or its home-qualified address when it sits outside that root. */
internal fun displayPath(root: Path?, path: Path): String {
    if (root == null) return path.abbreviateHome()
    return runCatching { root.relativize(path).toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && !it.startsWith("..") }
        ?: path.abbreviateHome()
}
