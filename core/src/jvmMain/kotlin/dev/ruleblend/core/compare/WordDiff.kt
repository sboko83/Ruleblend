package dev.ruleblend.core.compare

import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator

/** How a span participates in a word-level diff. */
enum class WordDiffKind { CONTEXT, ADDED, REMOVED }

/** A contiguous source or target span. Joining the spans reproduces the corresponding line. */
data class WordDiffSegment(
    val kind: WordDiffKind,
    val text: String,
)

/** Secondary, token-level difference for one changed pair of lines. */
object WordDiff {
    fun between(source: String, target: String): List<WordDiffSegment> {
        if (source == target) return listOf(WordDiffSegment(WordDiffKind.CONTEXT, source))
        val sourceTokens = tokens(source)
        val targetTokens = tokens(target)
        val edits = HistogramDiff().diff(TokenComparator, TokenSequence(sourceTokens), TokenSequence(targetTokens))
        val result = mutableListOf<WordDiffSegment>()
        var sourceIndex = 0
        for (edit in edits) {
            add(result, WordDiffKind.CONTEXT, sourceTokens.subList(sourceIndex, edit.beginA))
            add(result, WordDiffKind.REMOVED, sourceTokens.subList(edit.beginA, edit.endA))
            add(result, WordDiffKind.ADDED, targetTokens.subList(edit.beginB, edit.endB))
            sourceIndex = edit.endA
        }
        add(result, WordDiffKind.CONTEXT, sourceTokens.subList(sourceIndex, sourceTokens.size))
        return result
    }

    private fun add(target: MutableList<WordDiffSegment>, kind: WordDiffKind, tokens: List<String>) {
        if (tokens.isEmpty()) return
        val text = tokens.joinToString("")
        val previous = target.lastOrNull()
        if (previous?.kind == kind) {
            target[target.lastIndex] = previous.copy(text = previous.text + text)
        } else {
            target += WordDiffSegment(kind, text)
        }
    }

    private fun tokens(text: String): List<String> = tokenPattern.findAll(text).map { it.value }.toList()

    private val tokenPattern = Regex("""\s+|[\p{L}\p{N}_]+|.""")
}

private class TokenSequence(val tokens: List<String>) : Sequence() {
    override fun size(): Int = tokens.size
}

private object TokenComparator : SequenceComparator<TokenSequence>() {
    override fun equals(a: TokenSequence, ai: Int, b: TokenSequence, bi: Int): Boolean =
        a.tokens[ai] == b.tokens[bi]

    override fun hash(seq: TokenSequence, ptr: Int): Int = seq.tokens[ptr].hashCode()
}
