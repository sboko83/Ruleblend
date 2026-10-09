package dev.ruleblend.app.resolve

/**
 * What to do with one hand-edited block.
 *
 * Only these three: [RESTORE] takes the library body back, [SAVE_AS_VERSION] makes the edit the rule's
 * next library version, and [SKIP] leaves the file alone. There is no fourth answer core could give —
 * an ambiguous run has no attributable body to save and no safe body to overwrite, which is why it
 * never reaches a plan at all.
 */
enum class ResolveStrategy { RESTORE, SAVE_AS_VERSION, SKIP }

/** One block of one place, with the answer chosen for it. [ResolveStrategy.SKIP] never appears here. */
data class ResolveStep(val blockId: String, val blockName: String, val strategy: ResolveStrategy)

/**
 * The blocks of one place a batch will settle. The place is the unit, not the row: a forced write
 * rebuilds the whole managed run of every file of the target, so two rows of one place are one write
 * and cannot succeed separately.
 */
data class ResolvePlaceStep(val placeId: String, val placeName: String, val steps: List<ResolveStep>)

/**
 * What applying would do, resolved before anything is written.
 *
 * A conflict batch is the most destructive action in the app — it can discard edits somebody made by
 * hand in thirty files — so the sentence "restore 6, save 2, skip 4, across 5 places" has to exist
 * before the press, not after it.
 */
data class ResolvePlan(
    val places: List<ResolvePlaceStep> = emptyList(),
    /** Marked rows whose answer is [ResolveStrategy.SKIP]: counted so the plan can say what it leaves. */
    val skipped: Int = 0,
) {
    val restores: Int get() = count(ResolveStrategy.RESTORE)

    val saves: Int get() = count(ResolveStrategy.SAVE_AS_VERSION)

    val writes: Int get() = places.sumOf { it.steps.size }

    val empty: Boolean get() = places.isEmpty()

    private fun count(strategy: ResolveStrategy) =
        places.sumOf { place -> place.steps.count { it.strategy == strategy } }
}

/**
 * What a batch actually did. Failures are named by place rather than counted: "3 failed" is not
 * something a person can act on, and a place that refused its write is exactly where to look.
 */
data class ResolveReport(
    val restored: Int = 0,
    val saved: Int = 0,
    val failed: List<String> = emptyList(),
    val skipped: Int = 0,
)

/** Applies a plan through the existing write paths of core; the only writer this surface has. */
interface ResolveApplier {
    fun apply(plan: ResolvePlan): ResolveReport

    companion object {
        /** Used where resolving is out of scope: model tests of the read half, headless tooling. */
        val None = object : ResolveApplier {
            override fun apply(plan: ResolvePlan) = ResolveReport(skipped = plan.skipped)
        }
    }
}

/**
 * Turns marked rows into the writes a batch performs.
 *
 * [groups] must be the hand-edited class and nothing else: ambiguous and legacy rows do not enter a
 * plan and are not "skipped with an error" either — there is no strategy for them to refuse.
 *
 * Rows are deduplicated by [ResolveRow.key]: one block of one place is one write however many files of
 * that place hold it, because the write covers all of them.
 */
fun resolvePlan(
    groups: List<ResolveGroup>,
    marked: Set<String>,
    base: ResolveStrategy,
    overrides: Map<String, ResolveStrategy> = emptyMap(),
): ResolvePlan {
    var skipped = 0
    val places = groups.mapNotNull { group ->
        val steps = group.rows
            .filter { it.hasBlock && it.key in marked }
            .distinctBy { it.key }
            .mapNotNull { row ->
                when (val strategy = overrides[row.key] ?: base) {
                    ResolveStrategy.SKIP -> {
                        skipped++
                        null
                    }
                    else -> ResolveStep(row.blockId, row.blockName, strategy)
                }
            }
        if (steps.isEmpty()) null else ResolvePlaceStep(group.placeId, group.placeName, steps)
    }
    return ResolvePlan(places, skipped)
}

/** Every key of the hand-edited class: what "select all" means, and what a fresh scan can offer. */
fun resolveKeys(groups: List<ResolveGroup>): Set<String> =
    groups.flatMap { group -> group.rows.filter { it.hasBlock }.map { it.key } }.toSet()
