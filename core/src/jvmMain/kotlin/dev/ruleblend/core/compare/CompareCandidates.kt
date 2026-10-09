package dev.ruleblend.core.compare

import dev.ruleblend.core.model.Block

/** One catalog block together with the evidence for showing it in the compare picker. */
data class CompareCandidate(
    val block: Block,
    val similarity: SimilarityScore,
) {
    /** Whole-number similarity shown beside a top candidate. */
    val percent: Int get() = similarity.percent
}

/** Candidate picker sections: the best matches first, then the untouched catalog order. */
data class CompareCandidateList(
    val top: List<CompareCandidate>,
    val remaining: List<CompareCandidate>,
)

/**
 * Prepares library [catalog] entries for comparing with [source].
 *
 * The five highest [Similarity] scores become [CompareCandidateList.top]. Ties keep catalog order;
 * entries outside the top five stay in their original catalog order in [CompareCandidateList.remaining].
 * This only ranks candidates and deliberately does not apply the adopt rules in `BlockMatcher`.
 */
object CompareCandidates {
    const val TOP_LIMIT: Int = 5

    operator fun invoke(source: Block, catalog: List<Block>): CompareCandidateList {
        val candidates = catalog.mapIndexed { index, block ->
            index to CompareCandidate(block, Similarity.between(source.content, block.content))
        }
        val top = candidates
            .sortedWith(compareByDescending<Pair<Int, CompareCandidate>> { it.second.similarity.value }.thenBy { it.first })
            .take(TOP_LIMIT)
        val topIndexes = top.mapTo(mutableSetOf()) { it.first }
        return CompareCandidateList(
            top = top.map { it.second },
            remaining = candidates.filter { it.first !in topIndexes }.map { it.second },
        )
    }
}
