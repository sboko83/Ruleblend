package dev.ruleblend.core.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitterTest {

    @Test
    fun `no cuts returns the whole text as one section`() {
        val text = "one\ntwo\nthree"
        val parts = Splitter.split(text, emptyList())
        assertEquals(1, parts.size)
        assertEquals(text, parts[0].body)
        assertEquals("one", parts[0].displayTitle)
        assertEquals("one", parts[0].slug)
    }

    @Test
    fun `blank text returns one empty section even with cuts`() {
        val parts = Splitter.split("", listOf(1, 2))
        assertEquals(1, parts.size)
        assertEquals("", parts[0].body)
        assertEquals(0, parts[0].lineCount)
    }

    @Test
    fun `single cut yields two parts covering the input`() {
        val text = "alpha\nbeta\ngamma"
        val parts = Splitter.split(text, listOf(2))
        assertEquals(2, parts.size)
        assertEquals("alpha\nbeta", parts[0].body)
        assertEquals("gamma", parts[1].body)
    }

    @Test
    fun `duplicate and out-of-range cuts are dropped`() {
        val text = "a\nb\nc"
        val parts = Splitter.split(text, listOf(-1, 0, 1, 1, 3, 99))
        assertEquals(2, parts.size)
        assertEquals("a", parts[0].body)
        assertEquals("b\nc", parts[1].body)
    }

    @Test
    fun `adjacent cuts produce one-line parts`() {
        val text = "a\nb\nc"
        val parts = Splitter.split(text, listOf(1, 2))
        assertEquals(listOf("a", "b", "c"), parts.map { it.body })
    }

    @Test
    fun `part bodies are trimmed at both ends`() {
        val text = "\n\nalpha\n\n\nbeta\n\n"
        val parts = Splitter.split(text, listOf(4))
        assertEquals("alpha", parts[0].body)
        assertEquals("beta", parts[1].body)
    }

    @Test
    fun `part that trims to blank is kept for caller to flag`() {
        val text = "a\n\n\nb"
        val parts = Splitter.split(text, listOf(1, 3))
        assertEquals(3, parts.size)
        assertEquals("a", parts[0].body)
        assertEquals("", parts[1].body)
        assertEquals("b", parts[2].body)
    }

    @Test
    fun `slug is derived from first non-blank body line`() {
        val text = "Section One\ntext\nSection Two\nmore"
        val parts = Splitter.split(text, listOf(2))
        assertEquals("section-one", parts[0].slug)
        assertEquals("section-two", parts[1].slug)
    }

    @Test
    fun `every returned part has empty title and heading`() {
        val text = "a\nb\nc"
        Splitter.split(text, listOf(1, 2)).forEach {
            assertEquals("", it.title)
            assertEquals("", it.heading)
        }
    }

    @Test
    fun `splitSection reattaches the source heading to the first sub-section`() {
        val section = Section(
            title = "Coding",
            slug = "coding",
            heading = "## Coding",
            body = "indent 4\nline\nsemicolons no\nother",
            lineCount = 4,
        )
        val parts = Splitter.splitSection(section, listOf(2))
        assertEquals(2, parts.size)
        assertEquals("## Coding", parts[0].heading)
        assertEquals("Coding", parts[0].title)
        assertEquals("coding", parts[0].slug)
        assertEquals("indent 4\nline", parts[0].body)
        assertEquals("", parts[1].heading)
        assertEquals("", parts[1].title)
        assertEquals("semicolons no\nother", parts[1].body)
    }

    @Test
    fun `splitSection sourceText concat reproduces the source up to blank normalization`() {
        val section = Section(
            title = "H",
            slug = "h",
            heading = "# H",
            body = "one\ntwo\nthree\nfour",
            lineCount = 4,
        )
        val parts = Splitter.splitSection(section, listOf(2))
        val reconstructed = parts.joinToString("\n") { it.sourceText }
        assertEquals("# H\none\ntwo\nthree\nfour", reconstructed)
        assertEquals(section.sourceText, reconstructed)
    }

    @Test
    fun `splitSection without a heading behaves like split`() {
        val section = Section(title = "", slug = "one", heading = "", body = "one\ntwo\nthree", lineCount = 3)
        val parts = Splitter.splitSection(section, listOf(2))
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.heading.isEmpty() })
        assertTrue(parts.all { it.title.isEmpty() })
        assertEquals("one\ntwo", parts[0].body)
        assertEquals("three", parts[1].body)
    }

    @Test
    fun `splitSection with derived title takes the first non-blank body line`() {
        // Section had a heading but empty title (unusual but possible for a lone `#`); slug should
        // come from the first sub-section's first line.
        val section = Section(title = "", slug = "", heading = "#", body = "first\nsecond", lineCount = 2)
        val parts = Splitter.splitSection(section, listOf(1))
        assertEquals("first", parts[0].slug)
    }
}
