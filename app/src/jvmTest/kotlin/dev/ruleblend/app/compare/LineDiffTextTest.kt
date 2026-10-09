package dev.ruleblend.app.compare

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one read-only diff view feeds Place, Resolve and version history, so its rows are asserted
 * here rather than in each of those screens.
 */
class LineDiffTextTest {
    @Test fun pairsAReplacedLineAndMarksOnlyItsChangedWords() {
        val lines = renderedDiffLines("keep\ncolour is blue\n", "keep\ncolor is blue\n")

        assertEquals(
            listOf(RenderedDiffKind.CONTEXT, RenderedDiffKind.REMOVED, RenderedDiffKind.ADDED),
            lines.map { it.kind },
        )
        assertEquals(
            listOf("colour" to true, " is blue" to false),
            lines[1].segments.map { it.text to it.changed },
        )
        assertEquals(
            listOf("color" to true, " is blue" to false),
            lines[2].segments.map { it.text to it.changed },
        )
    }

    @Test fun keepsContextAroundAnInsertionAndADeletion() {
        val lines = renderedDiffLines("one\ndropped\ntwo\n", "one\ntwo\nadded\n")

        assertEquals(
            listOf(
                RenderedDiffKind.CONTEXT to "one",
                RenderedDiffKind.REMOVED to "dropped",
                RenderedDiffKind.CONTEXT to "two",
                RenderedDiffKind.ADDED to "added",
            ),
            lines.map { line -> line.kind to line.segments.joinToString("") { it.text } },
        )
    }

    @Test fun showsIdenticalTextAsContextOnly() {
        val lines = renderedDiffLines("same\ntext\n", "same\ntext\n")

        assertEquals(listOf("same", "text"), lines.map { line -> line.segments.single().text })
        assertEquals(listOf(RenderedDiffKind.CONTEXT, RenderedDiffKind.CONTEXT), lines.map { it.kind })
    }
}
