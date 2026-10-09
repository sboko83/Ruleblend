package dev.ruleblend.core.integration

/**
 * Thrown when adopting with replacement would have to move hand-written text: the steps at [blockers]
 * sit between sections that must become one managed run.
 */
class AdoptOrderException(val blockers: List<Int>) :
    IllegalArgumentException("Replacing these sections would move the text between them: steps $blockers")

/**
 * Where sections adopted with replacement land in an `rb1` file. The file holds a single run, so the
 * replaced sections and the run it already has must form one unbroken stretch of the file; only then
 * does every line keep its place. A kept section inside that stretch would have to move to one side
 * of the run, so such a plan is refused instead of written — the dialog asks for it before saving.
 *
 * Steps are the adoption plan in file order: their texts cover the file's unmanaged text line for line,
 * which is how the existing run is located among them from [UnmanagedContent.linesBeforeRun].
 */
object AdoptPlacement {

    /** Kept steps in the way of [replaced]; empty when the plan can be written without moving text. */
    fun blockers(texts: List<String>, replaced: List<Boolean>, linesBeforeRun: Int?): List<Int> =
        layout(texts, replaced, linesBeforeRun)?.blockers.orEmpty()

    /**
     * The plan split around the run: kept text [before], replaced steps that go [ahead] of the run's
     * current regions and [behind] them, kept text [after]. Null when nothing is replaced.
     */
    internal fun layout(texts: List<String>, replaced: List<Boolean>, linesBeforeRun: Int?): Layout? {
        require(texts.size == replaced.size) { "One replace flag per step" }
        val chosen = replaced.indices.filter { replaced[it] }
        if (chosen.isEmpty()) return null
        // Position 2i+1 is step i, position 2i the gap in front of it; the run occupies a gap, or sits
        // inside a step when that step's text lies on both sides of it.
        val runAt = runPosition(texts, linesBeforeRun)
        if (runAt != null && runAt % 2 == 1) return Layout(emptyList(), emptyList(), emptyList(), emptyList(), listOf(runAt / 2))
        val anchors = chosen.map { 2 * it + 1 } + listOfNotNull(runAt)
        val low = anchors.min()
        val high = anchors.max()
        val kept = texts.indices.filter { !replaced[it] && texts[it].isNotBlank() }
        return Layout(
            before = kept.filter { 2 * it + 1 < low },
            ahead = chosen.filter { runAt != null && 2 * it + 1 < runAt },
            behind = chosen.filter { runAt == null || 2 * it + 1 > runAt },
            after = kept.filter { 2 * it + 1 > high },
            blockers = kept.filter { 2 * it + 1 in low..high },
        )
    }

    private fun runPosition(texts: List<String>, linesBeforeRun: Int?): Int? {
        if (linesBeforeRun == null) return null
        var seen = 0
        texts.forEachIndexed { index, text ->
            if (seen == linesBeforeRun) return 2 * index
            seen += text.contentLines()
            if (seen > linesBeforeRun) return 2 * index + 1
        }
        return 2 * texts.size
    }

    internal class Layout(
        val before: List<Int>,
        val ahead: List<Int>,
        val behind: List<Int>,
        val after: List<Int>,
        val blockers: List<Int>,
    )
}

/** Lines that carry text: what locates a run among sections whose blank lines were trimmed. */
internal fun String.contentLines(): Int = lines().count { it.isNotBlank() }

/** The same lines, compared without the blank lines and trailing spaces a section split trims. */
internal fun String.textLines(): List<String> = lines().filter { it.isNotBlank() }.map { it.trimEnd() }
