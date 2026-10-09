package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.integration.UnmanagedFile

import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryFilters
import dev.ruleblend.app.library.LibraryObject
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryUsageIndex
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import java.nio.file.Path

/**
 * One library object as the palette shows it for the selected place. [status] is `null` when nothing
 * of the object sits here: such a row offers the object, it does not report it.
 */
data class PaletteRow(
    val key: LibraryObjectKey,
    val name: String,
    val version: String,
    val status: InstallStatus?,
    val favorite: Boolean = false,
)

/** One type group of the palette. [total] counts the matches, so a collapsed group still reports its size. */
data class PaletteSection(val kind: LibraryObjectKind, val rows: List<PaletteRow>) {
    val total: Int get() = rows.size
}

/**
 * "installed in 6 of 8 KMP apps": an object the siblings of this project's set already hold and this
 * place does not. The count comes from the usage index, not from a fresh walk over the sibling files.
 */
data class PaletteRecommendation(
    val key: LibraryObjectKey,
    val name: String,
    val installed: Int,
    val total: Int,
    val setName: String,
)

/**
 * A hand-written file found in the place, as the *Found in project* section shows it. [path] is what
 * the adopt dialog and the editor act on: the row names a file, and both actions open that same file.
 */
data class PaletteFoundFile(val name: String, val lines: Int, val path: Path)

/**
 * The library side of the palette: every object visible in this place, grouped by type and filtered
 * by [query]. Favorites lead their type, the rest follow by name. The catalog is the Library one — the palette must not become a second index of objects.
 *
 * [status] answers for a single object; a group has no status of its own, so it takes the worst of
 * its members and counts as present as soon as one member is, the same way the usage index reads a
 * group elsewhere.
 */
fun paletteSections(
    catalog: LibraryCatalog,
    query: String = "",
    status: (LibraryObjectKey) -> InstallStatus? = { null },
): List<PaletteSection> {
    val visible = catalog.filtered(LibraryFilters(query = query))
    return LibraryObjectKind.entries.map { kind ->
        PaletteSection(
            kind = kind,
            rows = visible.filter { it.key.kind == kind }
                .sortedWith(compareByDescending<LibraryObject> { it.favorite }.thenBy { it.name.lowercase() })
                .map { item ->
                    PaletteRow(
                        key = item.key,
                        name = item.name,
                        version = item.version,
                        status = item.statusIn(status),
                        favorite = item.favorite,
                    )
                },
        )
    }
}

/**
 * Objects the rest of this project's set already installed, ranked by how many siblings hold them.
 * Only a project in a set gets recommendations: an agent global has no peers to compare against, and
 * a project belongs to at most one set, so [total] is that set's size — including this project, the
 * way the mock-up phrases it ("in 6 of 8").
 *
 * Groups are left out: a group is a bundle whose members are recommended on their own, and offering
 * both would count the same install twice.
 */
fun paletteRecommendations(
    catalog: LibraryCatalog,
    board: PlaceBoard,
    target: Target,
    usage: LibraryUsageIndex,
    status: (LibraryObjectKey) -> InstallStatus? = { null },
    limit: Int = 3,
): List<PaletteRecommendation> {
    val project = target as? ProjectTarget ?: return emptyList()
    val key = project.dir.projectKey()
    val set = board.sets.find { key in it.projects } ?: return emptyList()
    val siblings = set.projects.filterNot { it == key }.mapTo(mutableSetOf()) { "project:$it" }
    if (siblings.isEmpty()) return emptyList()
    val here = placeId(target)
    return catalog.objects
        .filter {
            it.key.kind != LibraryObjectKind.GROUP && it.key.kind != LibraryObjectKind.PROFILE && it.statusIn(status) == null
        }
        .mapNotNull { item ->
            val installed = usage.sitesOf(item.key).count { it.placeId in siblings && it.placeId != here }
            if (installed == 0) return@mapNotNull null
            PaletteRecommendation(item.key, item.name, installed, set.projects.size, set.name)
        }
        .sortedWith(compareByDescending<PaletteRecommendation> { it.installed }.thenBy { it.name.lowercase() })
        .take(limit)
}

/**
 * Hand-written files of the place, owned and merely referenced alike, deduplicated by path. Only
 * files with text left to adopt are listed: a pure import pointer is already doing its job, and a
 * file kept in the model for its backup slot has nothing to show here.
 */
fun paletteFoundFiles(files: List<UnmanagedFile>): List<PaletteFoundFile> = files
    .filter { it.canAdopt && !it.isPointer }
    .distinctBy { it.path }
    .map { PaletteFoundFile(it.relative, it.content.lineCount, it.path) }

/**
 * Installed state per object, from the statuses the model already holds for this place: rules from
 * the managed regions, MCP servers and skills from their own install services. No extra file read.
 *
 * Every palette aggregate reads the place through this one function — a captured group must hold
 * exactly what the rows report as installed.
 */
fun IntegrationModel.paletteStatus(): (LibraryObjectKey) -> InstallStatus? = { key ->
    when (key.kind) {
        LibraryObjectKind.RULE -> statuses[key.id]
        LibraryObjectKind.SUBAGENT -> subagentStatuses[key.id]?.status
        LibraryObjectKind.MCP -> mcpStatuses[key.id]?.status
        LibraryObjectKind.SKILL -> skillStatuses[key.id]?.status
        LibraryObjectKind.GROUP -> null
        LibraryObjectKind.PROFILE -> null
    }
}

private fun LibraryObject.statusIn(status: (LibraryObjectKey) -> InstallStatus?): InstallStatus? =
    when (key.kind) {
        LibraryObjectKind.GROUP -> memberKeys.mapNotNull(status).maxByOrNull { it.rank() }
        else -> status(key)
    }

/** A hand edit must not hide behind a pending update, and neither behind a synced sibling. */
private fun InstallStatus.rank(): Int = when (this) {
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}
