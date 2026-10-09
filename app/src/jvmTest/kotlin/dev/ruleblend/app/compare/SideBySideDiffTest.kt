package dev.ruleblend.app.compare

import dev.ruleblend.core.compare.LineDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alignment is what makes the two columns readable: every row must mean the same slot on both
 * sides, and a line that exists on one side only must still occupy its row on the other.
 */
class SideBySideDiffTest {
    private fun rows(source: String, target: String) =
        sideBySideRows(source, target, LineDiff.between(source, target))

    @Test
    fun `identical bodies are all context and numbered in step`() {
        val rows = rows("a\nb\nc\n", "a\nb\nc\n")

        assertEquals(3, rows.size)
        assertTrue(rows.all { it.kind == DiffRowKind.CONTEXT })
        assertEquals(listOf(1, 2, 3), rows.map { it.leftNumber })
        assertEquals(listOf(1, 2, 3), rows.map { it.rightNumber })
    }

    @Test
    fun `a replaced line keeps both sides on one row and marks the changed words`() {
        val rows = rows("keep\nold value\n", "keep\nnew value\n")

        assertEquals(2, rows.size)
        val changed = rows[1]
        assertEquals(DiffRowKind.CHANGED, changed.kind)
        assertEquals(2, changed.leftNumber)
        assertEquals(2, changed.rightNumber)
        assertEquals("old value", changed.left.orEmpty().joinToString("") { it.text })
        assertEquals("new value", changed.right.orEmpty().joinToString("") { it.text })
        assertTrue(changed.left.orEmpty().any { it.changed })
        assertTrue(changed.right.orEmpty().any { it.changed })
    }

    @Test
    fun `an added line leaves the left side empty on that row`() {
        val rows = rows("a\n", "a\nb\n")

        assertEquals(2, rows.size)
        assertEquals(DiffRowKind.ADDED, rows[1].kind)
        assertNull(rows[1].left)
        assertNull(rows[1].leftNumber)
        assertEquals(2, rows[1].rightNumber)
    }

    @Test
    fun `a removed line leaves the right side empty on that row`() {
        val rows = rows("a\nb\n", "a\n")

        assertEquals(2, rows.size)
        assertEquals(DiffRowKind.REMOVED, rows[1].kind)
        assertNull(rows[1].right)
        assertEquals(2, rows[1].leftNumber)
        assertNull(rows[1].rightNumber)
    }

    @Test
    fun `only the first row of a hunk carries its transfer buttons`() {
        val rows = rows("a\nx\ny\nz\n", "a\n")
        val marked = rows.filter { it.hunkStart }

        assertEquals(1, marked.size)
        assertEquals(0, marked.single().hunkIndex)
        assertEquals(rows.indexOf(marked.single()), hunkRowIndex(rows, 0))
    }

    @Test
    fun `context after a hunk stays aligned even when the sides have different lengths`() {
        val rows = rows("a\nb\ntail\n", "a\nb\nb2\ntail\n")

        val tail = rows.last()
        assertEquals(DiffRowKind.CONTEXT, tail.kind)
        assertEquals("tail", tail.left.orEmpty().joinToString("") { it.text })
        assertEquals("tail", tail.right.orEmpty().joinToString("") { it.text })
    }

    @Test
    fun `every hunk reaches the rows so no difference is left without a control`() {
        val rows = rows("a\nb\nc\nd\n", "a\nB\nc\nD\n")
        val hunks = LineDiff.between("a\nb\nc\nd\n", "a\nB\nc\nD\n")

        assertEquals(hunks.indices.toList(), rows.filter { it.hunkStart }.map { it.hunkIndex })
    }
}
