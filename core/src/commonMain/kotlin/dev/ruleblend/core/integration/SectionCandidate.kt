package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block

/** What to do with an included section when saving the batch. */
enum class SectionAction {
    /** Skip: leave the library and the target file untouched for this section. */
    SKIP,

    /** Create a new block from the section body. */
    CREATE_NEW,

    /** Overwrite the matched block's content with the section body (id/name kept, version bumps). */
    OVERWRITE,
}

/**
 * One row of the adopt dialog's section list: a candidate [section], how it relates to the library
 * ([match]), whether the user has ticked it ([included]), and the [action] to take on save.
 *
 * Defaults when first computed:
 * - Not [Section.offerable] → included = false, action = [SectionAction.SKIP]; never matched and
 *   never shown — the candidate exists only so write-back keeps the section's source lines.
 * - [MatchKind.NEW] → included = true, action = [SectionAction.CREATE_NEW].
 * - [MatchKind.DUPLICATE] → included = false, action = [SectionAction.SKIP].
 * - [MatchKind.SIMILAR] → included = true, action = [SectionAction.OVERWRITE].
 */
data class SectionCandidate(
    val section: Section,
    val match: SectionMatch,
    val included: Boolean,
    val action: SectionAction,
) {

    companion object {
        /** Builds the initial candidate for [section] against [blocks], applying the defaults above. */
        fun initial(section: Section, blocks: List<Block>): SectionCandidate {
            if (!section.offerable) {
                val none = SectionMatch(MatchKind.NEW, null, 0.0)
                return SectionCandidate(section, none, included = false, action = SectionAction.SKIP)
            }
            val match = BlockMatcher.match(section, blocks)
            return when (match.kind) {
                MatchKind.NEW -> SectionCandidate(section, match, included = true, action = SectionAction.CREATE_NEW)
                MatchKind.DUPLICATE -> SectionCandidate(section, match, included = false, action = SectionAction.SKIP)
                MatchKind.SIMILAR -> SectionCandidate(section, match, included = true, action = SectionAction.OVERWRITE)
            }
        }
    }
}
