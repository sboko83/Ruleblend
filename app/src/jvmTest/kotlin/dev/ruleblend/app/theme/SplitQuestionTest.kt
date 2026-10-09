package dev.ruleblend.app.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class SplitQuestionTest {
    @Test
    fun `the question becomes the title and the rest its explanation`() {
        assertEquals(
            "Delete group \"a\"?" to "Rules are kept.",
            splitQuestion("Delete group \"a\"? Rules are kept."),
        )
    }

    @Test
    fun `a bare question has no explanation`() {
        assertEquals("Remove project \"a\" from Ruleblend?" to null, splitQuestion("Remove project \"a\" from Ruleblend?"))
    }

    @Test
    fun `a prompt without a question mark is kept whole`() {
        assertEquals("Stop managing" to null, splitQuestion("Stop managing"))
    }
}
