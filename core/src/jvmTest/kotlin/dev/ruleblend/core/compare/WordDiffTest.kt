package dev.ruleblend.core.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class WordDiffTest {
    @Test fun preservesBothLinesWhenItsSpansAreJoined() {
        val diff = WordDiff.between("Use a blue button.", "Use the green button!")

        assertEquals(
            "Use a blue button.",
            diff.filter { it.kind != WordDiffKind.ADDED }.joinToString("") { it.text },
        )
        assertEquals(
            "Use the green button!",
            diff.filter { it.kind != WordDiffKind.REMOVED }.joinToString("") { it.text },
        )
    }
}
