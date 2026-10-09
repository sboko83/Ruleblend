package dev.ruleblend.core.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectionExtractorTest {

    @Test
    fun `blank input returns one empty section`() {
        val sections = SectionExtractor.extract("")
        assertEquals(1, sections.size)
        assertEquals("", sections[0].title)
        assertEquals("", sections[0].body)
        assertEquals("", sections[0].heading)
        assertEquals(0, sections[0].lineCount)
    }

    @Test
    fun `flat text without headings becomes one section`() {
        val text = "Always use 4-space indent.\nCommit messages in English."
        val sections = SectionExtractor.extract(text)
        assertEquals(1, sections.size)
        assertEquals("", sections[0].title)
        assertEquals("", sections[0].heading)
        assertEquals(text, sections[0].body)
        // Untitled sections still get a slug from their first line, so dedup can find them.
        assertEquals("always-use-4-space-indent", sections[0].slug)
    }

    @Test
    fun `single heading yields one section covering the whole text`() {
        val text = "# Rules\n- Be concise.\n- No filler."
        val sections = SectionExtractor.extract(text)
        assertEquals(1, sections.size)
        assertEquals("Rules", sections[0].title)
        assertEquals("rules", sections[0].slug)
        // The heading is captured separately; the body holds only what followed it.
        assertEquals("# Rules", sections[0].heading)
        assertFalse(sections[0].body.contains("# Rules"), "heading must not duplicate into body")
        assertTrue(sections[0].body.contains("- Be concise."))
    }

    @Test
    fun `picks H1 when it yields two chunks`() {
        val text = """
            # Communication
            Be concise.

            # Coding
            Use 4-space indent.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("Communication", "Coding"), sections.map { it.title })
        assertEquals(listOf("communication", "coding"), sections.map { it.slug })
        assertEquals(listOf("# Communication", "# Coding"), sections.map { it.heading })
        assertEquals(listOf("Be concise.", "Use 4-space indent."), sections.map { it.body })
    }

    @Test
    fun `descends into a lone wrapper heading`() {
        // A single H1 over H2 subsections: the wrapper stays as a blank-body section (write-back
        // keeps its line) and the subsections become sections of their own.
        val text = """
            # Big Project

            ## Rules
            Be concise.

            ## Git
            Commit in English.

            ## Docs
            English only.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("Big Project", "Rules", "Git", "Docs"), sections.map { it.title })
        assertEquals("", sections[0].body)
        assertFalse(sections[0].offerable, "blank wrapper must not be offered")
        assertTrue(sections.drop(1).all { it.offerable })
    }

    @Test
    fun `wrapper intro text stays as the wrapper's own section`() {
        val text = """
            # Big Project
            Intro line about the project.

            ## Rules
            Be concise.

            ## Git
            Commit in English.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("Big Project", "Rules", "Git"), sections.map { it.title })
        assertEquals("Intro line about the project.", sections[0].body)
        assertTrue(sections[0].offerable)
    }

    @Test
    fun `splits at every heading regardless of count`() {
        // No artificial cap: 11 H1 headings each with a body become 11 sections.
        val text = (1..11).joinToString("\n\n") { "# Section $it\nbody $it" }
        val sections = SectionExtractor.extract(text)
        assertEquals(11, sections.size)
        assertEquals("Section 1", sections.first().title)
        assertEquals("Section 11", sections.last().title)
    }

    @Test
    fun `keeps empty-body sections for lossless write-back but does not offer them`() {
        val text = """
            # Empty

            # Real
            Has content.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("Empty", "Real"), sections.map { it.title })
        assertEquals("", sections[0].body)
        assertFalse(sections[0].offerable)
        assertTrue(sections[1].offerable)
        assertEquals("# Empty", sections[0].sourceText)
    }

    @Test
    fun `prefix before the first heading becomes a section with empty title`() {
        val text = """
            Always answer in Russian.

            # Rules
            Be concise.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(2, sections.size)
        assertEquals("", sections[0].title)
        assertEquals("", sections[0].heading)
        assertTrue(sections[0].body.contains("Always answer in Russian."))
        assertEquals("always-answer-in-russian", sections[0].slug)
        assertEquals("Rules", sections[1].title)
        assertEquals("# Rules", sections[1].heading)
    }

    @Test
    fun `comment-only prefix is kept but not offerable`() {
        val text = """
            <!-- GENERATED by `tool build` from template.md — edit rules/*.md, not this file. -->

            # Rules
            Be concise.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(2, sections.size)
        assertFalse(sections[0].offerable, "a generator banner is not a rule")
        assertTrue(sections[0].sourceText.contains("GENERATED"), "banner must survive for write-back")
        assertTrue(sections[1].offerable)
    }

    @Test
    fun `deeper headings stay inside bodies once a level yields two sections`() {
        val text = """
            # Top
            intro line.

            ## Nested
            nested body.

            # Second
            second body.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("Top", "Second"), sections.map { it.title })
        assertTrue(sections[0].body.contains("## Nested"), "nested heading must stay in body")
        assertTrue(sections[0].body.contains("nested body."))
    }

    @Test
    fun `never mixes content across sibling sections`() {
        // Mixed granularity: some H1 sections have H2 subsections, some do not. The split happens
        // at H1 only — nothing from one H1 section may leak into another or into a prefix.
        val text = """
            <!-- generated banner -->

            # Intro
            Intro paragraph.

            ## Who
            Some developer.

            # Plain One
            Body one.

            # With Subs

            ## Steps
            Step body.

            # Plain Two
            Body two.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(
            listOf("", "Intro", "Plain One", "With Subs", "Plain Two"),
            sections.map { it.title },
        )
        assertTrue(sections[1].body.contains("## Who"), "subsection stays inside its parent")
        assertEquals("Body one.", sections[2].body)
        assertTrue(sections[3].body.contains("## Steps"))
        assertEquals("Body two.", sections[4].body)
        // Every source line survives in exactly one section, in file order.
        val roundTrip = sections.joinToString("\n\n") { it.sourceText }
        assertTrue(roundTrip.contains("generated banner"))
        assertTrue(roundTrip.indexOf("# Plain One") < roundTrip.indexOf("# With Subs"))
    }

    @Test
    fun `prefix containing deeper headings keeps its structure`() {
        // A file that opens below the shallowest level: the pre-# text is split on its own.
        val text = """
            ## Early
            early body.

            # Main
            main body.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("Early", "Main"), sections.map { it.title })
        assertEquals(listOf("early body.", "main body."), sections.map { it.body })
    }

    @Test
    fun `a heading at the same level ends the current section`() {
        val text = """
            # First
            line a.

            # Second
            line b.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(2, sections.size)
        assertTrue(sections[0].body.contains("line a."))
        assertFalse(sections[0].body.contains("# First"), "split-section body drops its own heading")
        assertFalse(sections[0].body.contains("line b."))
    }

    @Test
    fun `lineCount matches body line count`() {
        val text = "# Rules\nline 1\nline 2\nline 3"
        val sections = SectionExtractor.extract(text)
        // Single heading at the only level → one section; body excludes the heading, so 3 lines.
        assertEquals(3, sections[0].lineCount)
    }

    @Test
    fun `sourceText reconstructs heading plus body for write-back`() {
        val text = "# Rules\nline 1\nline 2"
        val sections = SectionExtractor.extract(text)
        assertEquals("# Rules\nline 1\nline 2", sections[0].sourceText)
    }

    @Test
    fun `multi-section bodies exclude their headings`() {
        val text = """
            # A
            alpha body.

            # B
            beta body.
        """.trimIndent()
        val sections = SectionExtractor.extract(text)
        assertEquals(listOf("alpha body.", "beta body."), sections.map { it.body })
        assertEquals(listOf("# A\nalpha body.", "# B\nbeta body."), sections.map { it.sourceText })
    }

    @Test
    fun `slug derives from title via slugify`() {
        val text = "# Coding Guidelines!\nbody"
        val sections = SectionExtractor.extract(text)
        assertEquals("coding-guidelines", sections[0].slug)
    }

    @Test
    fun `trailing hashes in heading are stripped from title`() {
        val text = "# Rules ##\nbody"
        val sections = SectionExtractor.extract(text)
        assertEquals("Rules", sections[0].title)
    }
}
