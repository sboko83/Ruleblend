package dev.ruleblend.core.compare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineDiffTest {
    @Test fun buildsSeparateHunksAndWordDiffs() {
        val hunks = LineDiff.between(
            "title\nkeep\ncolour is blue\nend",
            "title\nkeep\ncolor is green\nend",
        )

        val hunk = hunks.single()
        assertEquals(LineRange(2, 3), hunk.source)
        assertEquals(LineRange(2, 3), hunk.target)
        assertEquals("colour is blue", hunk.changes.single().source)
        assertEquals("color is green", hunk.changes.single().target)
        assertEquals(
            listOf(
                WordDiffSegment(WordDiffKind.REMOVED, "colour"),
                WordDiffSegment(WordDiffKind.ADDED, "color"),
                WordDiffSegment(WordDiffKind.CONTEXT, " is "),
                WordDiffSegment(WordDiffKind.REMOVED, "blue"),
                WordDiffSegment(WordDiffKind.ADDED, "green"),
            ),
            hunk.changes.single().words,
        )
    }

    @Test fun appliesEveryHunkAndUndoesThemWithoutLosingText() {
        val source = "start\nold one\nkeep\nold two\nend\n"
        val target = "start\nnew one\nkeep\nnew two\nend"
        val hunks = LineDiff.between(source, target)

        val applied = hunks.fold(source) { draft, hunk -> HunkApply.forward(draft, hunk) }
        val undone = hunks.asReversed().fold(applied) { draft, hunk -> HunkApply.reverse(draft, hunk) }

        assertEquals(target, applied)
        assertEquals(source, undone)
    }

    @Test fun appliesAnInsertionAfterAnotherHunkShiftedTheDraft() {
        val source = "one\ntwo\nthree"
        val target = "one\nlong replacement\ntwo\ninserted\nthree"
        val hunks = LineDiff.between(source, target)

        val applied = hunks.fold(source) { draft, hunk -> HunkApply.forward(draft, hunk) }

        assertEquals(target, applied)
    }

    @Test fun carriesAMissingFinalLineBreakAsItsOwnHunk() {
        val source = "one\ntwo\n"
        val target = "one\ntwo"

        val hunk = LineDiff.between(source, target).single()

        assertEquals(TrailingLineBreak(source = true, target = false), hunk.trailingLineBreak)
        assertEquals(target, HunkApply.forward(source, hunk))
        assertEquals(source, HunkApply.reverse(target, hunk))
    }

    @Test fun addsAFinalLineBreakToAnOtherwiseEmptyText() {
        val hunk = LineDiff.between("", "\n").single()

        assertEquals("\n", HunkApply.forward("", hunk))
        assertEquals("", HunkApply.reverse("\n", hunk))
    }

    @Test fun refusesToApplyAChangedHunkOverUnrelatedDraftText() {
        val hunk = LineDiff.between("one\ntwo\nthree", "one\ndeux\nthree").single()

        assertFailsWith<HunkApplyException> {
            HunkApply.forward("one\nlocal edit\nthree", hunk)
        }
    }
}
