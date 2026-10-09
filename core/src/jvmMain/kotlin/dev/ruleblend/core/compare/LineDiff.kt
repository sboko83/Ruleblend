package dev.ruleblend.core.compare

import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.nio.charset.StandardCharsets

/** A half-open range of lines, counted from zero. */
data class LineRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0) { "A line range cannot start before the text." }
        require(endExclusive >= start) { "A line range cannot end before it starts." }
    }

    val count: Int get() = endExclusive - start
}

/** One changed source/target line pair inside a [LineHunk]. */
data class LineChange(
    val source: String?,
    val target: String?,
    /** Present only where one source line was replaced by one target line. */
    val words: List<WordDiffSegment> = emptyList(),
)

/** A final-line-break change that belongs to a hunk ending at end of file. */
data class TrailingLineBreak(
    val source: Boolean,
    val target: Boolean,
)

/**
 * One replaceable part of a line diff.
 *
 * [source] and [target] name the same change in their respective texts. The nearby unchanged lines
 * are anchors for applying this hunk after another hunk has shifted the draft.
 */
data class LineHunk(
    val source: LineRange,
    val target: LineRange,
    val changes: List<LineChange>,
    val beforeAnchor: String?,
    val afterAnchor: String?,
    val trailingLineBreak: TrailingLineBreak? = null,
) {
    val sourceLines: List<String> get() = changes.mapNotNull { it.source }
    val targetLines: List<String> get() = changes.mapNotNull { it.target }
}

/**
 * Line-level difference built with JGit's histogram algorithm.
 *
 * This is JVM-only because JGit is the library's desktop Git implementation. Text stays as UTF-8
 * `RawText`; no shell Git invocation or repository is involved.
 */
object LineDiff {
    fun between(source: String, target: String): List<LineHunk> {
        val sourceText = TextLines.of(source)
        val targetText = TextLines.of(target)
        val sourceRaw = RawText(source.toByteArray(StandardCharsets.UTF_8))
        val targetRaw = RawText(target.toByteArray(StandardCharsets.UTF_8))
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, sourceRaw, targetRaw)
        val hunks = edits.map { edit ->
            hunk(edit, sourceText, targetText)
        }.toMutableList()

        if (sourceText.trailingLineBreak != targetText.trailingLineBreak) {
            val last = hunks.lastOrNull()
            if (last != null &&
                last.source.endExclusive == sourceText.lines.size &&
                last.target.endExclusive == targetText.lines.size
            ) {
                hunks[hunks.lastIndex] = last.copy(
                    trailingLineBreak = TrailingLineBreak(sourceText.trailingLineBreak, targetText.trailingLineBreak),
                )
            } else {
                hunks += LineHunk(
                    source = LineRange(sourceText.lines.size, sourceText.lines.size),
                    target = LineRange(targetText.lines.size, targetText.lines.size),
                    changes = emptyList(),
                    beforeAnchor = sourceText.lines.lastOrNull(),
                    afterAnchor = null,
                    trailingLineBreak = TrailingLineBreak(sourceText.trailingLineBreak, targetText.trailingLineBreak),
                )
            }
        }
        return hunks
    }

    private fun hunk(edit: Edit, source: TextLines, target: TextLines): LineHunk {
        val sourceRange = LineRange(edit.beginA, edit.endA)
        val targetRange = LineRange(edit.beginB, edit.endB)
        val sourceLines = source.lines.subList(sourceRange.start, sourceRange.endExclusive)
        val targetLines = target.lines.subList(targetRange.start, targetRange.endExclusive)
        val changes = buildList {
            val paired = minOf(sourceLines.size, targetLines.size)
            for (index in 0 until paired) {
                add(LineChange(sourceLines[index], targetLines[index], WordDiff.between(sourceLines[index], targetLines[index])))
            }
            for (index in paired until sourceLines.size) add(LineChange(sourceLines[index], null))
            for (index in paired until targetLines.size) add(LineChange(null, targetLines[index]))
        }
        return LineHunk(
            source = sourceRange,
            target = targetRange,
            changes = changes,
            beforeAnchor = source.lines.getOrNull(sourceRange.start - 1),
            afterAnchor = source.lines.getOrNull(sourceRange.endExclusive),
        )
    }
}

internal data class TextLines(
    val lines: List<String>,
    val trailingLineBreak: Boolean,
) {
    fun render(): String = lines.joinToString("\n") + if (trailingLineBreak) "\n" else ""

    companion object {
        fun of(text: String): TextLines {
            if (text.isEmpty()) return TextLines(emptyList(), trailingLineBreak = false)
            val trailingLineBreak = text.endsWith('\n')
            val body = if (trailingLineBreak) text.dropLast(1) else text
            val lines = if (body.isEmpty()) listOf("") else body.split('\n')
            return TextLines(lines, trailingLineBreak)
        }
    }
}
