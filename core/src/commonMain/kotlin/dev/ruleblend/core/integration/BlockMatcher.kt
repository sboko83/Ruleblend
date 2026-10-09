package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.slugify

/** Relationship of a candidate section to the existing library. */
enum class MatchKind {
    /** No block with the same slug; a genuinely new rule. */
    NEW,

    /** Same slug, text near-identical — a re-import of an existing rule. */
    DUPLICATE,

    /** Same slug, text clearly related but not identical — probably an edited version. */
    SIMILAR,
}

/**
 * The result of comparing one section against the library.
 *
 * - [kind]: how the section relates to the library.
 * - [target]: the closest existing block, or `null` for [MatchKind.NEW].
 * - [score]: Jaccard similarity in `[0.0, 1.0]`; `0.0` for [MatchKind.NEW].
 */
data class SectionMatch(
    val kind: MatchKind,
    val target: Block?,
    val score: Double,
)

/**
 * Compares a section against the existing library by slug + text similarity.
 *
 * Slug match is the gate: only blocks whose `slugify(name)` equals the section's slug are
 * considered. Among them, [jaccard] over normalized word sets picks the closest, and the score
 * bucket gives [MatchKind.DUPLICATE] (≥ [DUPLICATE_THRESHOLD]), [MatchKind.SIMILAR]
 * (≥ [SIMILAR_THRESHOLD]), or [MatchKind.NEW].
 *
 * Pure: section + blocks in, match out. No I/O.
 */
object BlockMatcher {

    /** Jaccard at or above this is a duplicate of an existing rule. */
    const val DUPLICATE_THRESHOLD: Double = 0.85

    /** Jaccard at or above this (but below [DUPLICATE_THRESHOLD]) is a related but edited rule. */
    const val SIMILAR_THRESHOLD: Double = 0.5

    fun match(section: Section, blocks: List<Block>): SectionMatch {
        if (section.slug.isEmpty()) return SectionMatch(MatchKind.NEW, null, 0.0)

        val candidates = blocks.filter { slugify(it.name) == section.slug }
        if (candidates.isEmpty()) return SectionMatch(MatchKind.NEW, null, 0.0)

        val sectionWords = normalizedWords(section.body)
        var best: Block? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val score = jaccard(sectionWords, normalizedWords(candidate.content))
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        val kind = when {
            bestScore >= DUPLICATE_THRESHOLD -> MatchKind.DUPLICATE
            bestScore >= SIMILAR_THRESHOLD -> MatchKind.SIMILAR
            else -> MatchKind.NEW
        }
        // A near-zero score under the SIMILAR threshold means the shared name is a coincidence.
        val resolvedTarget = if (kind == MatchKind.NEW) null else best
        return SectionMatch(kind, resolvedTarget, bestScore)
    }

    /** Jaccard similarity `|A ∩ B| / |A ∪ B|` over two word sets; `0.0` when both are empty. */
    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val union = a.size + b.size - a.intersect(b).size
        if (union == 0) return 0.0
        return a.intersect(b).size.toDouble() / union
    }

    /**
     * Normalizes text into a bag of lowercase alphanumeric words: strips markdown/punctuation,
     * collapses whitespace, splits on spaces. Empties when the text has no usable words.
     */
    internal fun normalizedWords(text: String): Set<String> = text
        .lowercase()
        .replace("""[!-/:-@\[-`{-~\s]+""".toRegex(), " ")
        .trim()
        .split(' ')
        .filter { it.isNotEmpty() }
        .toSet()
}
