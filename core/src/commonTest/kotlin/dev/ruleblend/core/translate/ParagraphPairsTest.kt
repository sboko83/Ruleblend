package dev.ruleblend.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParagraphPairsTest {

    private val source = """
        # Heading

        First paragraph, wrapped
        over two lines.

        - list item one
        - list item two

        ```kotlin
        fun main() {

            println("blank line inside a fence")
        }
        ```

        Last paragraph.
    """.trimIndent()

    @Test
    fun `blocks are cut at blank lines, not inside a fence`() {
        val blocks = paragraphs(source)
        assertEquals(5, blocks.size)
        assertEquals("# Heading", blocks[0])
        assertEquals("- list item one\n- list item two", blocks[2])
        assertTrue(blocks[3].startsWith("```kotlin") && blocks[3].lines().any { it.isBlank() })
        assertEquals("Last paragraph.", blocks[4])
    }

    @Test
    fun `a translation of the same document pairs block by block`() {
        val translated = TranslationDocument.of(source).let { document ->
            document.assemble(document.requests.map { it.uppercase() })
        }
        val pairs = alignParagraphs(source, translated)
        assertEquals(5, pairs.size)
        assertEquals("# Heading", pairs[0].source)
        assertEquals("# HEADING", pairs[0].target)
        // The fence is copied through, so the two sides stay identical there.
        assertEquals(pairs[3].source, pairs[3].target)
    }

    @Test
    fun `a missing translation keeps the blocks and leaves the other side empty`() {
        val pairs = alignParagraphs(source, null)
        assertEquals(5, pairs.size)
        assertEquals(listOf<String?>(null, null, null, null, null), pairs.map { it.target })
    }

    @Test
    fun `blocks that do not line up fall back to one pair instead of a wrong partner`() {
        val pairs = alignParagraphs(source, "Only one block came back.")
        assertEquals(1, pairs.size)
        assertEquals(source, pairs[0].source)
        assertEquals("Only one block came back.", pairs[0].target)
    }

    @Test
    fun `an empty document still answers with one pair`() {
        assertEquals(listOf(TranslatedParagraph("", null)), alignParagraphs("", null))
    }
}
