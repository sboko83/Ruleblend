package dev.ruleblend.core.integration

/**
 * One piece of a target file in file order, for a read-only preview of what an agent actually reads.
 * [Hand] is the user's own text, [Run] is a stretch of consecutive Ruleblend-managed regions —
 * the split the whole ownership model rests on, shown rather than described.
 */
sealed interface FileSegment {
    /** Hand-written text: trimmed of the blank lines that belong to the markers around it. */
    data class Hand(val text: String) : FileSegment

    /** Consecutive managed regions. A drifted run still lists its blocks, from the manifest. */
    data class Run(val regions: List<ManagedRegion>) : FileSegment
}
