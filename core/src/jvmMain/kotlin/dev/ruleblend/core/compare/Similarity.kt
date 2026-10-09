package dev.ruleblend.core.compare

import kotlin.math.roundToInt

/** Word and character evidence used to rank comparison candidates. */
data class SimilarityScore(
    val wordJaccard: Double,
    val trigramJaccard: Double,
) {
    /** Equal weight prevents either wording changes or short textual edits from deciding alone. */
    val value: Double get() = (wordJaccard + trigramJaccard) / 2
    val percent: Int get() = (value * 100).roundToInt()
}

/** Pure similarity calculation; ranking and candidate selection belong to the next compare step. */
object Similarity {
    fun between(source: String, target: String): SimilarityScore = SimilarityScore(
        wordJaccard = jaccard(words(source), words(target)),
        trigramJaccard = jaccard(trigrams(source), trigrams(target)),
    )

    private fun words(text: String): Set<String> = wordPattern.findAll(text.lowercase()).map { it.value }.toSet()

    private fun trigrams(text: String): Set<String> {
        val normalized = whitespace.replace(text.lowercase(), " ").trim()
        return when {
            normalized.isEmpty() -> emptySet()
            normalized.length < 3 -> setOf(normalized)
            else -> normalized.windowed(3).toSet()
        }
    }

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        val shared = left.intersect(right).size
        return shared.toDouble() / (left.size + right.size - shared)
    }

    private val wordPattern = Regex("""[\p{L}\p{N}_]+""")
    private val whitespace = Regex("""\s+""")
}
