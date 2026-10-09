package dev.ruleblend.app.place

import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.core.integration.Target

/** Where a section comes from. Only [SET] carries a user-given name; the rest are fixed headers. */
enum class PlaceSectionKind { PINNED, RECENT, AGENTS, SET, UNGROUPED }

/** One row of the place list. [marks] is everything the place holds, summed up for its dot. */
data class PlaceEntry(
    val id: String,
    val name: String,
    val target: Target,
    val marks: Set<StatusMark>,
    val pinned: Boolean,
)

data class PlaceSection(
    val key: String,
    val kind: PlaceSectionKind,
    /** Set name for [PlaceSectionKind.SET]; `null` for the fixed sections, whose title is localized. */
    val name: String? = null,
    val places: List<PlaceEntry> = emptyList(),
    /** Members before the query filter, so a collapsed set still reports its real size. */
    val total: Int = places.size,
)

/**
 * The place list in display order: shortcuts first (pinned, recent), then the canonical listing of
 * agent globals, project sets and ungrouped projects.
 *
 * Pinned and Recent are views over the same places the canonical sections hold, so a place can show
 * twice on purpose — that is what a shortcut is. Recent drops what is pinned, because a pin already
 * keeps the place one section above.
 *
 * Places always come from [targets]: the list must not become a third reading of "which places exist".
 * An id in the board that no longer resolves is skipped rather than repaired here — the config is
 * cleaned where a project is removed, not while rendering.
 */
fun placeSections(
    targets: ConfiguredTargets,
    board: PlaceBoard,
    query: String = "",
    /**
     * The place currently open, kept in the list whatever the query says: a filter that hides where
     * the reader already is leaves the surface with no answer to "which place am I reading".
     */
    keep: String? = null,
    marks: (Target) -> Set<StatusMark> = { emptySet() },
): List<PlaceSection> {
    val pinned = board.pinned.toSet()
    val entries = targets.all.associate { target ->
        val id = placeId(target)
        id to PlaceEntry(id, target.name, target, marks(target), id in pinned)
    }
    val byProject = targets.projects.associateBy { it.dir.projectKey() }
    val grouped = board.sets.flatMap { it.projects }.toSet()
    val trimmed = query.trim().lowercase()

    fun List<PlaceEntry>.matching(): List<PlaceEntry> = when {
        trimmed.isEmpty() -> this
        else -> filter { it.id == keep || trimmed in it.name.lowercase() || trimmed in it.id.lowercase() }
    }

    fun section(key: String, kind: PlaceSectionKind, all: List<PlaceEntry>, name: String? = null) =
        PlaceSection(key, kind, name, all.matching(), all.size)

    fun entriesOf(places: List<Target>) = places.mapNotNull { entries[placeId(it)] }

    return buildList {
        add(section("pinned", PlaceSectionKind.PINNED, board.pinned.mapNotNull(entries::get)))
        add(section("recent", PlaceSectionKind.RECENT, board.recent.filterNot { it in pinned }.mapNotNull(entries::get)))
        add(section("agents", PlaceSectionKind.AGENTS, entriesOf(targets.agents)))
        board.sets.forEach { set ->
            add(
                section(
                    key = "set:${set.name}",
                    kind = PlaceSectionKind.SET,
                    all = entriesOf(set.projects.mapNotNull(byProject::get)),
                    name = set.name,
                ),
            )
        }
        add(
            section(
                key = "ungrouped",
                kind = PlaceSectionKind.UNGROUPED,
                all = entriesOf(targets.projects.filter { it.dir.projectKey() !in grouped }),
            ),
        )
    }.filter { it.places.isNotEmpty() || trimmed.isEmpty() && it.kind == PlaceSectionKind.SET }
}
