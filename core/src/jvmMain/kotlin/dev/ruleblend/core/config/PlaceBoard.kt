package dev.ruleblend.core.config

import kotlinx.serialization.Serializable

/** How many visited places the list keeps. Older entries fall off the end. */
const val RECENT_PLACES_LIMIT = 5

/**
 * A named set of projects — the unit of work at scale, so an action targets "KMP apps" instead of
 * eight projects one by one. Members are project keys ([projectKey]): the same absolute, normalized
 * paths [AppConfig.projects] holds, never an agent global file.
 */
@Serializable
data class ProjectSet(val name: String, val projects: List<String> = emptyList())

/**
 * Machine-local navigation state of the place list: what is pinned, what was visited last, and how
 * projects are grouped into sets. [pinned] and [recent] hold place ids as the Place surface builds
 * them, because a pin may point at an agent global file too.
 *
 * Nothing here is content: a set is a view of the machine's checkouts, so it never travels with an
 * export the way a group of library objects does.
 */
@Serializable
data class PlaceBoard(
    val pinned: List<String> = emptyList(),
    val recent: List<String> = emptyList(),
    val sets: List<ProjectSet> = emptyList(),
)

/** Pins an unpinned place, unpins a pinned one. Pins keep insertion order. */
fun PlaceBoard.withPinToggled(placeId: String): PlaceBoard =
    if (placeId in pinned) copy(pinned = pinned - placeId) else copy(pinned = pinned + placeId)

/** Most recent first, capped at [RECENT_PLACES_LIMIT]. A repeat visit moves a place up, never doubles it. */
fun PlaceBoard.withVisited(placeId: String): PlaceBoard =
    copy(recent = (listOf(placeId) + (recent - placeId)).take(RECENT_PLACES_LIMIT))

/** Forgets a place that left the config; its set membership is dropped by [withProjectInSet]. */
fun PlaceBoard.withoutPlace(placeId: String): PlaceBoard =
    copy(pinned = pinned - placeId, recent = recent - placeId)

/** Adds an empty set. A duplicate or blank name is ignored — sets are addressed by name. */
fun PlaceBoard.withSet(name: String): PlaceBoard = when {
    name.isBlank() || sets.any { it.name == name } -> this
    else -> copy(sets = sets + ProjectSet(name))
}

/** Renames a set, keeping its members and position. A blank or taken name leaves the board as is. */
fun PlaceBoard.withSetRenamed(from: String, to: String): PlaceBoard = when {
    to.isBlank() || from == to || sets.none { it.name == from } || sets.any { it.name == to } -> this
    else -> copy(sets = sets.map { if (it.name == from) it.copy(name = to) else it })
}

/** Drops a set. Its projects stay configured and fall back to the ungrouped section. */
fun PlaceBoard.withoutSet(name: String): PlaceBoard = copy(sets = sets.filterNot { it.name == name })

/**
 * Moves [projectKey] into [setName], or out of every set when it is `null`. A project belongs to at
 * most one set: the list is a tree, and the same project under two headers would make "where does
 * this install go" ambiguous for both the user and Coverage.
 */
fun PlaceBoard.withProjectInSet(projectKey: String, setName: String?): PlaceBoard = copy(
    sets = sets.map { set ->
        when {
            set.name != setName -> set.copy(projects = set.projects - projectKey)
            projectKey in set.projects -> set
            else -> set.copy(projects = set.projects + projectKey)
        }
    },
)

/** The set holding [projectKey], or `null` when it is ungrouped. */
fun PlaceBoard.setOf(projectKey: String): String? = sets.find { projectKey in it.projects }?.name
