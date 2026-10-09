package dev.ruleblend.app.coverage

import dev.ruleblend.app.place.projectPlaceId
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryObject
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.integration.InstallStatus

/**
 * What a column stands for. [PLACE] is one place a wider column was opened into; only [SET] and
 * [PLACE] carry a name of their own, the rest are localized headers.
 */
enum class CoverageColumnKind { AGENT, SET, UNGROUPED, PLACE }

/** What a row stands for: everything, one type of object, one library group, or one object. */
enum class CoverageRowKind { ALL, TYPE, GROUP, OBJECT }

/**
 * One column of the matrix. A column is a *set of places*, never a place: 25 projects in 5 sets are
 * 5 columns, which is what keeps the 304 × 30 grid from ever being drawn. Opening one replaces it
 * with its places instead of adding a second column model — a [PLACE] column is the same shape with
 * one id in it, so every cell rule keeps working unchanged.
 */
data class CoverageColumn(
    val id: String,
    val kind: CoverageColumnKind,
    /** `null` for the fixed headers (merged agents, ungrouped projects), whose title is localized. */
    val name: String?,
    val placeIds: List<String>,
    /** Set on a [PLACE] column: the column it was opened out of, and that title collapses it again. */
    val parentId: String? = null,
    val parentName: String? = null,
    val parentKind: CoverageColumnKind? = null,
    /** A one-place group keeps its group name and separately names the destination below it. */
    val placeName: String? = null,
) {
    val places: Int get() = placeIds.size

    /** The one place this column stands for, or `null` while it still aggregates several. */
    val placeId: String? get() = placeIds.singleOrNull()

    /** Only a column holding more than one place has anything to open. */
    val expandable: Boolean get() = parentId == null && places > 1
}

/**
 * One aggregate cell: [installed] of [members] counted units present in the column — library objects
 * for an aggregate row, places for an opened object row, which is the only reading that answers
 * "in how many projects of this set does this rule sit".
 *
 * [members] counts only objects that *can* live in the column — a project-scoped rule outside its
 * project and an object whose agent has no destination are not gaps to close, so they are left
 * out of the denominator entirely; a column where no member fits at all is [outOfScope].
 *
 * The status split is counted per object with the worst place winning, the same rule every other
 * surface uses when one object sits in several files.
 */
data class CoverageCell(
    val installed: Int = 0,
    val members: Int = 0,
    val synced: Int = 0,
    val updates: Int = 0,
    val modified: Int = 0,
) {
    val outOfScope: Boolean get() = members == 0

    val missing: Int get() = members - installed

    val status: InstallStatus? get() = when {
        modified > 0 -> InstallStatus.MODIFIED
        updates > 0 -> InstallStatus.UPDATE_AVAILABLE
        installed > 0 -> InstallStatus.SYNCED
        else -> null
    }
}

/** One row with its cells already aggregated; [cells] is aligned with [CoverageSnapshot.columns]. */
data class CoverageRow(
    val id: String,
    val kind: CoverageRowKind,
    /** `null` for the fixed rows (all objects, one type), whose title is localized. */
    val name: String?,
    /** Set for [CoverageRowKind.TYPE], so the screen can title and glyph the row. */
    val objectKind: LibraryObjectKind? = null,
    val members: List<LibraryObjectKey> = emptyList(),
    val cells: List<CoverageCell> = emptyList(),
    /** Set on a [CoverageRowKind.OBJECT] row: the one object it stands for, and its library version. */
    val key: LibraryObjectKey? = null,
    val version: String? = null,
    /** The row this one was opened out of; also what its title collapses. */
    val parentId: String? = null,
) {
    val total: Int get() = members.size

    val installedAnywhere: Boolean get() = cells.any { it.installed > 0 }

    /** Types and groups open into objects; opening all objects would duplicate the type rows. */
    val expandable: Boolean get() = (kind == CoverageRowKind.TYPE || kind == CoverageRowKind.GROUP) && members.isNotEmpty()

    /**
     * Whether the row can be ticked for a bulk action. Every row but "all objects" can: a mass action
     * needs a set the user named or narrowed to, and "everything in the library into this set" is a
     * request the plan should not make easy to press.
     */
    val markable: Boolean get() = kind != CoverageRowKind.ALL && members.isNotEmpty()
}

/**
 * Which aggregates are opened, by row and column id. View state, not config: it re-cuts the scan
 * already in memory the same way a filter does, and is never written anywhere.
 */
data class CoverageExpansion(
    val rows: Set<String> = emptySet(),
    val columns: Set<String> = emptySet(),
) {
    fun toggleRow(id: String) = copy(rows = if (id in rows) rows - id else rows + id)

    fun toggleColumn(id: String) = copy(columns = if (id in columns) columns - id else columns + id)
}

/** How many occurrences of one status the fleet holds, and in how many places. */
data class CoverageTally(val count: Int = 0, val agents: Int = 0, val projects: Int = 0) {
    val places: Int get() = agents + projects
}

/** Row and column filters. An empty [kinds] means every type; the toggles are view state, not config. */
data class CoverageFilters(
    val kinds: Set<LibraryObjectKind> = emptySet(),
    /** Drops rows nothing of which is installed anywhere — the "what do I already use" reading. */
    val onlyInstalled: Boolean = false,
    /** Keeps only rows with outdated or locally modified installations. */
    val needsAttention: Boolean = false,
)

/** One agent as the visibility panel shows it. Hiding writes `hiddenAgents`, shared with Place and Home. */
data class CoverageAgent(
    val id: String,
    val name: String,
    val visible: Boolean,
    val skills: Boolean,
)

/**
 * What Coverage shows after one scan. [scanned] false means "not read yet", which is not the same as
 * an empty matrix: a fleet with no places at all is a different answer and gets a different state.
 */
data class CoverageSnapshot(
    val columns: List<CoverageColumn> = emptyList(),
    val rows: List<CoverageRow> = emptyList(),
    val agents: List<CoverageAgent> = emptyList(),
    val places: Int = 0,
    val agentPlaces: Int = 0,
    val projectPlaces: Int = 0,
    val objects: Int = 0,
    val conflicts: CoverageTally = CoverageTally(),
    val updates: CoverageTally = CoverageTally(),
    val scanned: Boolean = false,
) {
    companion object {
        val EMPTY = CoverageSnapshot()
    }
}

/** Whether any visible aggregate can be opened or closed, without reading the fleet again. */
data class CoverageFold(
    val rowsExpandable: Boolean,
    val rowsCollapsible: Boolean,
    val columnsExpandable: Boolean,
    val columnsCollapsible: Boolean,
)

fun CoverageSnapshot.fold(expansion: CoverageExpansion): CoverageFold = CoverageFold(
    rowsExpandable = rows.any { it.expandable && it.id !in expansion.rows },
    rowsCollapsible = rows.any { it.expandable && it.id in expansion.rows },
    columnsExpandable = columns.any { it.expandable && it.id !in expansion.columns },
    columnsCollapsible = columns.any { it.parentId != null },
)

/**
 * Builds the matrix from the library catalog and one cross-place scan. Pure: no disk is read here,
 * so changing a filter re-aggregates the scan already in memory instead of walking the fleet again.
 *
 * Every number comes from [scanned] and [catalog] — the same two read-models Library and Place use.
 * Coverage deliberately owns no index of its own: a second answer to "where is this installed" is a
 * second answer that can be wrong.
 */
fun coverageMatrix(
    catalog: LibraryCatalog,
    scanned: List<LibraryPlaceUsage>,
    board: PlaceBoard = PlaceBoard(),
    agents: List<CoverageAgent> = emptyList(),
    filters: CoverageFilters = CoverageFilters(),
    expansion: CoverageExpansion = CoverageExpansion(),
): CoverageSnapshot {
    val places = scanned.associateBy { it.id }
    val byKey = catalog.objects.associateBy { it.key }
    val columns = columns(scanned, board, expansion)
    val rows = rows(catalog, filters, expansion, byKey)
        .map { row -> row.copy(cells = columns.map { column -> row.cell(column, places, byKey) }) }
        .filter { !filters.onlyInstalled || it.installedAnywhere }
        .filter { !filters.needsAttention || it.cells.any { cell -> cell.updates > 0 || cell.modified > 0 } }
    return CoverageSnapshot(
        columns = columns,
        rows = rows,
        agents = agents,
        places = scanned.size,
        agentPlaces = scanned.count { it.kind == LibraryPlaceKind.AGENT },
        projectPlaces = scanned.count { it.kind == LibraryPlaceKind.PROJECT },
        objects = catalog.objects.count { it.key.kind != LibraryObjectKind.GROUP && it.key.kind != LibraryObjectKind.PROFILE },
        conflicts = scanned.tally(InstallStatus.MODIFIED),
        updates = scanned.tally(InstallStatus.UPDATE_AVAILABLE),
        scanned = true,
    )
}

/**
 * Agent globals first, then the named sets in their configured order, then the projects no set
 * claimed — the order Home's fleet strip and the place list already read in. An opened column is
 * replaced by its places in its own position, so the fleet order survives the drill-down.
 *
 * An empty set produces no column: a header over nothing is not a coverage answer.
 */
private fun columns(
    scanned: List<LibraryPlaceUsage>,
    board: PlaceBoard,
    expansion: CoverageExpansion,
): List<CoverageColumn> {
    val places = scanned.associateBy { it.id }
    return aggregateColumns(scanned, board).flatMap { column ->
        if (!column.expandable || column.id !in expansion.columns) {
            listOf(column.copy(placeName = column.placeId?.let { places[it]?.name ?: it }))
        }
        else column.placeIds.map { placeId ->
            CoverageColumn(
                id = "place:$placeId",
                kind = CoverageColumnKind.PLACE,
                name = places[placeId]?.name ?: placeId,
                placeIds = listOf(placeId),
                parentId = column.id,
                parentName = column.name,
                parentKind = column.kind,
                placeName = places[placeId]?.name ?: placeId,
            )
        }
    }
}

private fun aggregateColumns(
    scanned: List<LibraryPlaceUsage>,
    board: PlaceBoard,
): List<CoverageColumn> {
    val agents = scanned.filter { it.kind == LibraryPlaceKind.AGENT }
    val known = scanned.mapTo(mutableSetOf()) { it.id }
    val inSets = board.sets.flatMap { it.projects }.map(::projectPlaceId).toSet()
    return buildList {
        if (agents.isNotEmpty()) {
            add(CoverageColumn("agents", CoverageColumnKind.AGENT, agents.singleOrNull()?.name, agents.map { it.id }))
        }
        board.sets.forEach { set ->
            val members = set.projects.map(::projectPlaceId).filter { it in known }
            if (members.isNotEmpty()) {
                add(CoverageColumn("set:${set.name}", CoverageColumnKind.SET, set.name, members))
            }
        }
        val ungrouped = scanned.filter { it.kind == LibraryPlaceKind.PROJECT && it.id !in inSets }
        if (ungrouped.isNotEmpty()) {
            add(CoverageColumn("ungrouped", CoverageColumnKind.UNGROUPED, null, ungrouped.map { it.id }))
        }
    }
}

/**
 * Coarse to fine: everything, then one row per type, then the library groups. Groups are the rows a
 * user names themselves, so they keep the library's own order; a group whose members are all filtered
 * out disappears rather than showing a row of dashes.
 */
private fun rows(
    catalog: LibraryCatalog,
    filters: CoverageFilters,
    expansion: CoverageExpansion,
    byKey: Map<LibraryObjectKey, LibraryObject>,
): List<CoverageRow> {
    val kinds = filters.kinds.ifEmpty { setOf(LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.SKILL, LibraryObjectKind.MCP) }
    val visible = catalog.objects.filter {
        it.key.kind != LibraryObjectKind.GROUP && it.key.kind != LibraryObjectKind.PROFILE && it.key.kind in kinds
    }
    val members = visible.map { it.key }
    val aggregates = buildList {
        if (members.isNotEmpty()) add(CoverageRow("all", CoverageRowKind.ALL, null, members = members))
        listOf(LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.SKILL, LibraryObjectKind.MCP)
            .filter { it in kinds }
            .forEach { kind ->
                val typed = members.filter { it.kind == kind }
                if (typed.isNotEmpty()) {
                    add(CoverageRow("type:$kind", CoverageRowKind.TYPE, null, objectKind = kind, members = typed))
                }
            }
        catalog.objects.filter { it.key.kind == LibraryObjectKind.GROUP }.forEach { group ->
            val typed = group.memberKeys.filter { it.kind in kinds }
            if (typed.isNotEmpty()) {
                add(CoverageRow("group:${group.key.id}", CoverageRowKind.GROUP, group.name, members = typed))
            }
        }
    }
    return aggregates.flatMap { row ->
        if (!row.expandable || row.id !in expansion.rows) listOf(row)
        else listOf(row) + row.members.mapNotNull { key -> byKey[key]?.objectRow(row.id) }
    }
}

/**
 * One object under the row it was opened from. The id carries the parent, so the same object opened
 * under two rows stays two distinct lazy items instead of one that jumps.
 */
private fun LibraryObject.objectRow(parentId: String) = CoverageRow(
    id = "$parentId/${key.kind}:${key.id}",
    kind = CoverageRowKind.OBJECT,
    name = name,
    objectKind = key.kind,
    members = listOf(key),
    key = key,
    version = version,
    parentId = parentId,
)

/**
 * An aggregate row counts objects; an opened object counts places. Both ask the same question of the
 * same [LibraryObject.fitsIn], so a dash in the drill-down can never contradict the aggregate above.
 */
private fun CoverageRow.cell(
    column: CoverageColumn,
    places: Map<String, LibraryPlaceUsage>,
    byKey: Map<LibraryObjectKey, LibraryObject>,
): CoverageCell =
    if (kind == CoverageRowKind.OBJECT) objectCell(key ?: return CoverageCell(), column, places, byKey)
    else cell(members, column, places, byKey)

private fun cell(
    members: List<LibraryObjectKey>,
    column: CoverageColumn,
    places: Map<String, LibraryPlaceUsage>,
    byKey: Map<LibraryObjectKey, LibraryObject>,
): CoverageCell {
    val columnPlaces = column.placeIds.mapNotNull(places::get)
    var inScope = 0
    var installed = 0
    var synced = 0
    var updates = 0
    var modified = 0
    members.forEach { key ->
        val item = byKey[key] ?: return@forEach
        var fits = false
        var worst: InstallStatus? = null
        columnPlaces.forEach { place ->
            if (!item.fitsIn(place)) return@forEach
            fits = true
            val status = place.installs[key]?.status ?: return@forEach
            if (worst == null || status.severity() > worst.severity()) worst = status
        }
        if (fits) inScope++
        when (worst) {
            null -> Unit
            InstallStatus.SYNCED -> synced++
            InstallStatus.UPDATE_AVAILABLE -> updates++
            InstallStatus.MODIFIED -> modified++
        }
        if (worst != null) installed++
    }
    return CoverageCell(installed, inScope, synced, updates, modified)
}

/**
 * One object across the places of a column: [CoverageCell.members] is how many of them can hold it at
 * all, so a column where none can is out of scope and shows a dash — the same answer the aggregate
 * above gives, reached through the same rule.
 */
private fun objectCell(
    key: LibraryObjectKey,
    column: CoverageColumn,
    places: Map<String, LibraryPlaceUsage>,
    byKey: Map<LibraryObjectKey, LibraryObject>,
): CoverageCell {
    val item = byKey[key] ?: return CoverageCell()
    var inScope = 0
    var installed = 0
    var synced = 0
    var updates = 0
    var modified = 0
    column.placeIds.mapNotNull(places::get).forEach { place ->
        if (!item.fitsIn(place)) return@forEach
        inScope++
        when (place.installs[key]?.status) {
            null -> return@forEach
            InstallStatus.SYNCED -> synced++
            InstallStatus.UPDATE_AVAILABLE -> updates++
            InstallStatus.MODIFIED -> modified++
        }
        installed++
    }
    return CoverageCell(installed, inScope, synced, updates, modified)
}

/**
 * Whether clicking this cell may write. Only an opened object in a single place is a write: an
 * aggregate cell stands for several objects or places at once, and guessing which one the click meant
 * is exactly the silent mass edit Coverage must not perform. An out-of-scope cell is refused here, in
 * the model, so the write path is never asked to reject it.
 */
fun CoverageRow.writable(column: CoverageColumn, cell: CoverageCell): Boolean =
    kind == CoverageRowKind.OBJECT && key != null && column.placeId != null && !cell.outOfScope

/**
 * The two rules the model refuses to let a click break, shown rather than explained: a rule pinned to
 * a project belongs to that project alone, and every object type needs an agent destination.
 *
 * Shared with the bulk plan so a batch counts a pair out of scope exactly where a cell draws a dash.
 */
internal fun LibraryObject.fitsIn(place: LibraryPlaceUsage): Boolean = when {
    scope != null -> place.id == projectPlaceId(scope)
    key.kind == LibraryObjectKind.SKILL -> place.supportsSkills
    key.kind == LibraryObjectKind.MCP -> place.supportsMcp
    key.kind == LibraryObjectKind.SUBAGENT -> place.supportsSubagents
    else -> true
}

/** Occurrences and the places holding them, counted the way the Home cards count their classes. */
private fun List<LibraryPlaceUsage>.tally(status: InstallStatus): CoverageTally {
    val counts = map { place -> place.installs.count { it.value.status == status } }
    return CoverageTally(
        count = counts.sum(),
        agents = indices.count { counts[it] > 0 && this[it].kind == LibraryPlaceKind.AGENT },
        projects = indices.count { counts[it] > 0 && this[it].kind == LibraryPlaceKind.PROJECT },
    )
}

private fun InstallStatus?.severity(): Int = when (this) {
    null -> -1
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}
