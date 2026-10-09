package dev.ruleblend.app.place

import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.core.integration.InstallStatus

/**
 * Membership of a group as this place would fill it: rules and MCP servers are both blocks, skills
 * are their own list, which is exactly how [dev.ruleblend.core.model.Group] stores them.
 */
data class PlaceMembers(
    val blockIds: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
) {
    val size: Int get() = blockIds.size + skillIds.size

    val isEmpty: Boolean get() = size == 0
}

/**
 * What the place holds right now, ready to be saved as a group: every rule, skill and MCP server
 * with an install status here, in catalog order.
 *
 * A group is not captured — its members are, so the saved group stays a flat list of what an agent
 * actually reads in this place rather than a bundle nested inside a bundle.
 */
fun placeMembers(
    catalog: LibraryCatalog,
    status: (LibraryObjectKey) -> InstallStatus?,
): PlaceMembers {
    val here = catalog.objects
        .map { it.key }
        .filter { it.kind != LibraryObjectKind.GROUP && it.kind != LibraryObjectKind.PROFILE && status(it) != null }
    return PlaceMembers(
        blockIds = here.filter { it.kind != LibraryObjectKind.SKILL }.map { it.id },
        skillIds = here.filter { it.kind == LibraryObjectKind.SKILL }.map { it.id },
    )
}
