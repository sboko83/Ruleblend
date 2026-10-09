package dev.ruleblend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillTest {

    @Test
    fun `frontmatter reads common discovery fields`() {
        assertEquals(
            SkillFrontmatter("review", "Review changes safely", "2.1"),
            parseSkillFrontmatter("---\nname: review\ndescription: Review changes safely\nversion: 2.1\nagent: codex\n---\n# Review\n"),
        )
    }

    @Test
    fun `local document has agent discovery metadata`() {
        val document = localSkillDocument("code-review", "Review changes safely", "Code Review")

        assertTrue("name: \"code-review\"" in document)
        assertTrue("description: \"Review changes safely\"" in document)
        assertTrue(document.endsWith("# Code Review\n"))
    }

    @Test
    fun `metadata update preserves body and extra frontmatter`() {
        val source = """
            |---
            |name: old
            |description: old description
            |compatibility: codex
            |---
            |
            |# Instructions
            |Keep this.
        """.trimMargin().trimStart()

        val updated = updateSkillDocumentMetadata(source, "new-name", "Use when needed")

        assertTrue("compatibility: codex" in updated)
        assertTrue("# Instructions\nKeep this." in updated)
        assertTrue("name: \"new-name\"" in updated)
        assertTrue("description: \"Use when needed\"" in updated)
    }

    @Test
    fun `skill name validation rejects non portable names`() {
        assertTrue(validSkillName("review-2"))
        assertEquals(false, validSkillName("Review 2"))
        assertFailsWith<IllegalArgumentException> { localSkillDocument("Review 2") }
    }
}
