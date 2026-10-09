package dev.ruleblend.core.compare

/** The draft no longer contains the source side of a hunk, so applying it would overwrite an edit. */
class HunkApplyException(message: String) : IllegalArgumentException(message)

/** Applies one [LineHunk] in either direction while preserving all unrelated draft text. */
object HunkApply {
    fun forward(text: String, hunk: LineHunk): String = apply(
        text = text,
        expected = hunk.sourceLines,
        replacement = hunk.targetLines,
        preferredStart = hunk.source.start,
        hunk = hunk,
        trailingLineBreak = hunk.trailingLineBreak?.target,
    )

    fun reverse(text: String, hunk: LineHunk): String = apply(
        text = text,
        expected = hunk.targetLines,
        replacement = hunk.sourceLines,
        preferredStart = hunk.target.start,
        hunk = hunk,
        trailingLineBreak = hunk.trailingLineBreak?.source,
    )

    private fun apply(
        text: String,
        expected: List<String>,
        replacement: List<String>,
        preferredStart: Int,
        hunk: LineHunk,
        trailingLineBreak: Boolean?,
    ): String {
        val draft = TextLines.of(text)
        val index = findMatch(draft.lines, expected, preferredStart, hunk)
        val result = draft.lines.toMutableList().apply {
            subList(index, index + expected.size).clear()
            addAll(index, replacement)
        }
        return TextLines(result, trailingLineBreak ?: draft.trailingLineBreak).render()
    }

    private fun findMatch(
        lines: List<String>,
        expected: List<String>,
        preferredStart: Int,
        hunk: LineHunk,
    ): Int {
        val candidates = (0..lines.size).filter { start ->
            start + expected.size <= lines.size &&
                lines.subList(start, start + expected.size) == expected &&
                (hunk.beforeAnchor == null || start > 0 && lines[start - 1] == hunk.beforeAnchor) &&
                (hunk.afterAnchor == null || start + expected.size < lines.size &&
                    lines[start + expected.size] == hunk.afterAnchor)
        }
        if (candidates.isEmpty()) {
            throw HunkApplyException("The draft no longer contains this hunk's source text.")
        }
        return candidates.minBy { kotlin.math.abs(it - preferredStart) }
    }
}
