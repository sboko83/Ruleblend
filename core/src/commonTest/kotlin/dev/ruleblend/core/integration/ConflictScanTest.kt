package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictScanTest {
    private val alpha = Block(id = "alpha", name = "Alpha", version = 1, content = "alpha body")
    private val beta = Block(id = "beta", name = "Beta", version = 2, content = "beta body")
    private val library = listOf(alpha, beta).associateBy { it.id }
    private val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)

    private fun file(vararg blocks: Block): String =
        blocks.fold("# Notes\n") { text, block -> encoding.upsert(text, regionFor(block)) }

    @Test fun untouchedFileHasNoConflicts() {
        assertEquals(emptyList(), classifyConflicts(file(alpha, beta), library::get))
    }

    @Test fun outdatedBlockIsAnUpdateNotAConflict() {
        val text = file(alpha)
        val conflicts = classifyConflicts(text) { mapOf("alpha" to alpha.copy(version = 3)).get(it) }
        assertEquals(emptyList(), conflicts, "a newer library version is an update, never a hand edit")
    }

    @Test fun handEditedBlockIsClassifiedWithItsDiff() {
        val text = file(alpha, beta).replace("beta body", "beta body\nlocal exception")

        val conflicts = classifyConflicts(text, library::get)

        assertEquals(1, conflicts.size)
        val conflict = conflicts.single()
        assertEquals("beta", conflict.blockId)
        assertEquals(2, conflict.version)
        assertEquals(ConflictKind.HAND_EDITED, conflict.kind)
        assertEquals(1, conflict.added)
        assertEquals(0, conflict.removed)
        assertEquals(
            listOf(ConflictLineKind.CONTEXT, ConflictLineKind.ADDED),
            conflict.diff.map { it.kind },
        )
    }

    @Test fun twoEditedBlocksOfOneRunAreAmbiguous() {
        // Both bodies changed, so core cannot attribute either edit to one rule — and neither may the
        // screen: an ambiguous run carries no diff to act on.
        val text = file(alpha, beta)
            .replace("alpha body", "alpha body edited")
            .replace("beta body", "beta body edited")

        val conflicts = classifyConflicts(text, library::get)

        assertEquals(listOf("alpha", "beta"), conflicts.map { it.blockId })
        assertTrue(conflicts.all { it.kind == ConflictKind.AMBIGUOUS_RUN })
        assertTrue(conflicts.all { it.diff.isEmpty() })
    }

    @Test fun editIsIsolatedEvenWhenItBrokeTheManifestOffsets() {
        // A length-changing edit makes the byte offsets unusable; the untouched neighbour still anchors
        // the edited body, which is exactly what keeps this case out of the ambiguous class.
        val text = file(alpha, beta).replace("alpha body", "alpha body, extended by hand")

        val conflict = classifyConflicts(text, library::get).single()

        assertEquals("alpha", conflict.blockId)
        assertEquals(ConflictKind.HAND_EDITED, conflict.kind)
        assertEquals(1, conflict.added)
        assertEquals(1, conflict.removed)
    }

    @Test fun blockMissingFromTheLibraryIsNotAConflict() {
        val text = file(alpha).replace("alpha body", "alpha body edited")
        assertEquals(emptyList(), classifyConflicts(text) { null })
    }

    @Test fun legacyMarkersClassifyTheirOwnEdits() {
        val text = LegacyMarkers.upsert("", regionFor(alpha)).replace("alpha body", "alpha edited")

        val conflict = classifyConflicts(text, library::get).single()

        assertEquals("alpha", conflict.blockId)
        assertEquals(ConflictKind.HAND_EDITED, conflict.kind)
    }

    @Test fun diffPairsReplacedLinesAndKeepsContext() {
        val diff = diffLines("one\ntwo\nthree", "one\ndeux\nthree")

        assertEquals(
            listOf(
                ConflictLine(ConflictLineKind.CONTEXT, "one"),
                ConflictLine(ConflictLineKind.REMOVED, "two"),
                ConflictLine(ConflictLineKind.ADDED, "deux"),
                ConflictLine(ConflictLineKind.CONTEXT, "three"),
            ),
            diff,
        )
    }

    @Test fun identicalBodiesDiffAsContextOnly() {
        assertTrue(diffLines("a\nb", "a\nb").all { it.kind == ConflictLineKind.CONTEXT })
    }
}
