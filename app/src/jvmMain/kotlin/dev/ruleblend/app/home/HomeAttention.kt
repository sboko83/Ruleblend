package dev.ruleblend.app.home

import dev.ruleblend.app.place.projectPlaceId
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.PlaceFile
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.TargetOwnershipMode

/**
 * A class of problem, not a single alert: one card stands for every occurrence of its kind across
 * every place. Ordered by how much a user loses by ignoring it — a hand edit is overwritten on the
 * next apply, a pending update only means being behind.
 */
enum class AttentionKind { CONFLICTS, UPDATES, UNADOPTED, LEGACY, NEW_AGENT }

/** One place inside a card. [count] is measured in the card's own unit — see [AttentionCard.total]. */
data class AttentionRow(
    val placeId: String,
    val name: String,
    val count: Int,
    /** Files the count came from, for the row's second line; empty for status-based cards. */
    val files: List<String> = emptyList(),
)

/**
 * One class of problem with everything needed to render its card and act on it.
 *
 * [total] is counted in the unit of the kind: hand-edited blocks, available updates, hand-written
 * lines outside managed regions, files still in the legacy marker format, projects an unmanaged
 * agent could be enabled for.
 */
data class AttentionCard(
    val kind: AttentionKind,
    val total: Int,
    val rows: List<AttentionRow> = emptyList(),
    /** Set for [AttentionKind.NEW_AGENT] only: which agent was detected. */
    val agent: DetectedAgent? = null,
) {
    val places: Int get() = rows.size
}

/**
 * An agent installed on the machine that Ruleblend does not manage yet. Detected outside the pure
 * model, because deciding it needs the agent's global file on disk.
 */
data class DetectedAgent(
    val id: String,
    val name: String,
    /** Where the card sends the user: the agent's global file is a place like any other. */
    val placeId: String,
    val globalFile: String,
    val eligibleProjects: Int,
)

/** One place in the fleet strip: how its installed objects split across the three statuses. */
data class FleetPlace(
    val id: String,
    val name: String,
    val kind: LibraryPlaceKind,
    val synced: Int,
    val updates: Int,
    val modified: Int,
    /** Files of the place that are on disk and hold no managed region at all. */
    val untouched: Int = 0,
) {
    val total: Int get() = synced + updates + modified

    /** Everything the place holds, summed up for its dot — the same marks the place list uses. */
    val marks: Set<StatusMark> get() = buildSet {
        if (synced > 0) add(StatusMark.MANAGED)
        if (modified > 0) add(StatusMark.CONFLICT)
        if (updates > 0) add(StatusMark.UPDATE)
        if (untouched > 0 || total == 0) add(StatusMark.UNMANAGED)
    }
}

/** Where a fleet group comes from. Only [SET] carries a user-given name; the rest are fixed headers. */
enum class FleetGroupKind { AGENTS, SET, UNGROUPED }

/** One header of the fleet strip. The same grouping the place list uses, so both read alike. */
data class FleetGroup(
    val kind: FleetGroupKind,
    /** Set name for [FleetGroupKind.SET]; `null` for the fixed headers, whose title is localized. */
    val name: String? = null,
    val places: List<FleetPlace> = emptyList(),
)

/** Fleet health: the one line that answers "how are all my places doing" without opening any. */
data class FleetHealth(val groups: List<FleetGroup> = emptyList()) {
    /** Every place once, in display order; the counters below are counted over this, not per group. */
    val places: List<FleetPlace> = groups.flatMap { it.places }

    val total: Int get() = places.size
    val clean: Int get() = places.count { it.marks == setOf(StatusMark.MANAGED) }
    val withUpdates: Int get() = places.count { it.updates > 0 }
    val withConflicts: Int get() = places.count { it.modified > 0 }
}

/**
 * Size of the library behind the fleet. An empty library is a Home state of its own: there is
 * nothing to install anywhere yet, so "nothing needs attention" would be the wrong answer.
 */
data class LibraryTotals(
    val rules: Int = 0,
    val skills: Int = 0,
    val mcpServers: Int = 0,
    val groups: Int = 0,
) {
    val objects: Int get() = rules + skills + mcpServers
    val isEmpty: Boolean get() = objects == 0
}

/** One portable profile attached to a project, ready for Home to render without another config read. */
data class HomeProfileBinding(
    val id: String,
    val name: String,
    val active: Boolean,
)

/** A project Home can switch between at least two already attached profiles. */
data class HomeProfileProject(
    /** Normalized project path; this is the config key and the target for a profile switch. */
    val projectKey: String,
    val name: String,
    val profiles: List<HomeProfileBinding>,
    /** Visible project agents whose adapter says a session restart is needed after a switch. */
    val restartAgents: List<String> = emptyList(),
)

/** The last completed switch, kept separately from a scan so its restart notice is intentional. */
data class HomeProfileChange(
    val projectKey: String,
    val profileId: String,
)

/**
 * What Home shows after one scan. [cards] empty is the "all clear" state — a scanned fleet with
 * nothing to do, which is not the same as [scanned] being false.
 */
data class HomeSnapshot(
    val cards: List<AttentionCard> = emptyList(),
    val fleet: FleetHealth = FleetHealth(),
    /** Pinned first, then recent minus the pinned — the shortcuts the place list already keeps. */
    val shortcuts: List<FleetPlace> = emptyList(),
    /** Only projects with two or more attached profiles belong in Home's switching panel. */
    val profileProjects: List<HomeProfileProject> = emptyList(),
    val library: LibraryTotals = LibraryTotals(),
    /** False until the first scan finishes; an empty snapshot before that means "unknown". */
    val scanned: Boolean = false,
) {
    val allClear: Boolean get() = scanned && cards.isEmpty()

    fun card(kind: AttentionKind): AttentionCard? = cards.firstOrNull { it.kind == kind }

    companion object {
        val EMPTY = HomeSnapshot()
    }
}

/**
 * Turns one cross-place scan into the attention feed. Pure: everything it decides comes from
 * [scanned] and [agents], so the counters can never drift from the scan they were computed on, and
 * nothing here touches disk during recomposition.
 *
 * Places with nothing installed and nothing hand-written produce no rows at all — an untouched
 * project is not a problem, it is a project that has not been set up yet.
 */
fun homeSnapshot(
    scanned: List<LibraryPlaceUsage>,
    agents: List<DetectedAgent> = emptyList(),
    board: PlaceBoard = PlaceBoard(),
    library: LibraryTotals = LibraryTotals(),
    profileProjects: List<HomeProfileProject> = emptyList(),
): HomeSnapshot {
    val cards = buildList {
        statusCard(AttentionKind.CONFLICTS, scanned, InstallStatus.MODIFIED)?.let(::add)
        statusCard(AttentionKind.UPDATES, scanned, InstallStatus.UPDATE_AVAILABLE)?.let(::add)
        fileCard(AttentionKind.UNADOPTED, scanned) { it.unmanagedLines }?.let(::add)
        fileCard(AttentionKind.LEGACY, scanned) { if (it.mode == TargetOwnershipMode.LEGACY) 1 else 0 }?.let(::add)
        // One card per agent: enabling Kimi Code and enabling ZCode are separate decisions.
        agents.forEach { add(AttentionCard(AttentionKind.NEW_AGENT, it.eligibleProjects, agent = it)) }
    }
    val fleet = scanned.map { it.fleetPlace() }
    return HomeSnapshot(
        cards = cards,
        fleet = FleetHealth(fleet.grouped(board)),
        shortcuts = fleet.shortcuts(board),
        library = library,
        profileProjects = profileProjects,
        scanned = true,
    )
}

/**
 * Agent globals first, then the named sets in their configured order, then whatever is ungrouped —
 * the same order [dev.ruleblend.app.place.placeSections] produces, because a fleet that reads in one
 * order on Home and another in Place is two different fleets to the eye.
 *
 * A set membership is a project key, and a project place id is that key with a prefix, so nothing
 * here re-derives which places exist: the scan is the only source.
 */
private fun List<FleetPlace>.grouped(board: PlaceBoard): List<FleetGroup> {
    val byId = associateBy { it.id }
    val inSets = board.sets.flatMap { it.projects }.map(::projectPlaceId).toSet()
    val agents = filter { it.kind == LibraryPlaceKind.AGENT }
    val ungrouped = filter { it.kind == LibraryPlaceKind.PROJECT && it.id !in inSets }
    val sets = board.sets.map { set ->
        FleetGroup(FleetGroupKind.SET, set.name, set.projects.mapNotNull { byId[projectPlaceId(it)] })
    }
    return buildList {
        add(FleetGroup(FleetGroupKind.AGENTS, places = agents))
        addAll(sets)
        add(FleetGroup(FleetGroupKind.UNGROUPED, places = ungrouped))
    }.filter { it.places.isNotEmpty() }
}

/** Pins keep their order; a pinned place is not repeated under recent, where it would say nothing new. */
private fun List<FleetPlace>.shortcuts(board: PlaceBoard): List<FleetPlace> {
    val byId = associateBy { it.id }
    val recent = board.recent.filterNot { it in board.pinned }
    return (board.pinned + recent).mapNotNull(byId::get)
}

/** Places counted by how many of their installed objects sit in [status]. */
private fun statusCard(
    kind: AttentionKind,
    scanned: List<LibraryPlaceUsage>,
    status: InstallStatus,
): AttentionCard? {
    val rows = scanned.mapNotNull { place ->
        val count = place.installs.count { it.value.status == status }
        if (count == 0) null else AttentionRow(place.id, place.name, count)
    }
    return rows.card(kind)
}

/** Places counted by a per-file measure; a file contributing 0 leaves no trace in the row. */
private fun fileCard(
    kind: AttentionKind,
    scanned: List<LibraryPlaceUsage>,
    measure: (PlaceFile) -> Int,
): AttentionCard? {
    val rows = scanned.mapNotNull { place ->
        val counted = place.files.map { it to measure(it) }.filter { it.second > 0 }
        if (counted.isEmpty()) return@mapNotNull null
        AttentionRow(place.id, place.name, counted.sumOf { it.second }, counted.map { it.first.name })
    }
    return rows.card(kind)
}

/** Rows are ordered worst first: the card's own list is already the "where do I start" answer. */
private fun List<AttentionRow>.card(kind: AttentionKind): AttentionCard? {
    if (isEmpty()) return null
    val ordered = sortedWith(compareByDescending<AttentionRow> { it.count }.thenBy { it.name.lowercase() })
    return AttentionCard(kind, ordered.sumOf { it.count }, ordered)
}

private fun LibraryPlaceUsage.fleetPlace(): FleetPlace {
    val counts = installs.values.groupingBy { it.status }.eachCount()
    return FleetPlace(
        id = id,
        name = name,
        kind = kind,
        synced = counts[InstallStatus.SYNCED] ?: 0,
        updates = counts[InstallStatus.UPDATE_AVAILABLE] ?: 0,
        modified = counts[InstallStatus.MODIFIED] ?: 0,
        untouched = files.count { it.exists && it.mode == TargetOwnershipMode.NONE },
    )
}
