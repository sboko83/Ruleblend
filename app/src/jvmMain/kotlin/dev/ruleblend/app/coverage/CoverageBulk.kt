package dev.ruleblend.app.coverage

import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.usecase.SkipReason

/**
 * What a marked set of rows is asked to do to the fleet.
 *
 * [UPDATE] is not a fourth kind of write: it is [INSTALL] over the copies the scan already calls
 * outdated, which is why it needs no target — "everywhere" means every place that holds an outdated
 * copy. A hand-edited copy is never one of them; the installer refuses it as modified, and the plan
 * does not offer it either.
 */
enum class CoverageBulkAction { INSTALL, REMOVE, UPDATE }

/** One place of a bulk action with exactly the objects it is to receive or give up. */
data class CoverageBulkStep(
    val placeId: String,
    val placeName: String,
    val keys: List<LibraryObjectKey>,
)

/**
 * What a bulk action would do, resolved before anything is written.
 *
 * The plan is the whole point of the mass action: an aggregate row can stand for two hundred objects
 * and a column for thirty places, so "install into this set" has to say how many writes that is
 * before it performs them. Pairs that cannot be written are counted out here — [outOfScope] is the
 * project-pinned rule and the skill-less agent, refused by the same rule the matrix draws as a dash.
 *
 * A pair already in the state the action asks for is left out rather than rewritten: installing what
 * is installed would inflate the report with writes that changed nothing.
 */
data class CoverageBulkPlan(
    val action: CoverageBulkAction,
    /**
     * Id of the column the action was aimed at; `null` for [CoverageBulkAction.UPDATE], which has no
     * target. An id and not a title: the fixed columns are titled by the screen, not by the model.
     */
    val target: String? = null,
    val steps: List<CoverageBulkStep> = emptyList(),
    val outOfScope: Int = 0,
    /** Marked objects the plan started from, whether or not any of them survived into a step. */
    val objects: Int = 0,
) {
    val writes: Int get() = steps.sumOf { it.keys.size }

    val places: Int get() = steps.size

    val empty: Boolean get() = steps.isEmpty()
}

/**
 * What a bulk action actually did. Counted per pair like a single install, plus the places that were
 * planned for and took nothing: "partly done" is only useful with a where, and a silent shortfall is
 * the failure mode that makes mass actions untrustworthy.
 */
data class CoverageBulkReport(
    val action: CoverageBulkAction,
    val written: Int = 0,
    val skipped: Map<SkipReason, Int> = emptyMap(),
    val failed: Int = 0,
    val refused: List<String> = emptyList(),
)

/**
 * The objects behind the ticked rows. An aggregate row contributes its members and an opened object
 * contributes itself, which is the same field in both cases — so a row ticked while collapsed means
 * exactly what it means while opened, and ticking a group and one of its members counts that member
 * once.
 */
fun coverageMarkedKeys(rows: List<CoverageRow>, marked: Set<String>): List<LibraryObjectKey> =
    rows.filter { it.id in marked }.flatMap { it.members }.distinct()

/**
 * Splits the marked objects and one target column into the single "object × place" writes 7B performs
 * one at a time. No new operation is introduced: a mass install is the same write repeated, which is
 * what lets one refusal stand alone instead of taking the rest of the batch down with it.
 */
fun coverageBulkPlan(
    action: CoverageBulkAction,
    keys: List<LibraryObjectKey>,
    column: CoverageColumn?,
    catalog: LibraryCatalog,
    scanned: List<LibraryPlaceUsage>,
): CoverageBulkPlan {
    val byKey = catalog.objects.associateBy { it.key }
    val byId = scanned.associateBy { it.id }
    // Update reaches the whole fleet; the other two go exactly as wide as the column that was chosen.
    val places = when (action) {
        CoverageBulkAction.UPDATE -> scanned
        else -> column?.placeIds?.mapNotNull(byId::get).orEmpty()
    }
    var outOfScope = 0
    val steps = places.mapNotNull { place ->
        val fits = keys.filter { byKey[it]?.fitsIn(place) == true }
        if (action == CoverageBulkAction.INSTALL) outOfScope += keys.size - fits.size
        val take = when (action) {
            CoverageBulkAction.INSTALL -> fits.filter { place.installs[it] == null }
            CoverageBulkAction.REMOVE -> keys.filter { place.installs[it] != null }
            CoverageBulkAction.UPDATE ->
                keys.filter { place.installs[it]?.status == InstallStatus.UPDATE_AVAILABLE }
        }
        if (take.isEmpty()) null else CoverageBulkStep(place.id, place.name, take)
    }
    return CoverageBulkPlan(
        action = action,
        target = column?.id,
        steps = steps,
        outOfScope = outOfScope,
        objects = keys.size,
    )
}
