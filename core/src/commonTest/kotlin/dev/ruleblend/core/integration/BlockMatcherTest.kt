package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockMatcherTest {

    private fun section(title: String, body: String) =
        Section(title = title, slug = dev.ruleblend.core.model.slugify(title), heading = "# $title", body = body, lineCount = 1)

    private fun block(name: String, content: String, id: String = dev.ruleblend.core.model.slugify(name)) =
        Block(id = id, name = name, content = content)

    @Test
    fun `no slug candidate is NEW`() {
        val s = section("Unique Rule", "Be concise.")
        val result = BlockMatcher.match(s, listOf(block("Coding Guidelines", "Use 4-space indent.")))
        assertEquals(MatchKind.NEW, result.kind)
        assertNull(result.target)
    }

    @Test
    fun `empty slug is NEW regardless of library`() {
        val s = Section("", "", "", "Some body text.", 1)
        val result = BlockMatcher.match(s, listOf(block("Anything", "Some body text.")))
        assertEquals(MatchKind.NEW, result.kind)
    }

    @Test
    fun `exact text under same slug is DUPLICATE`() {
        val body = "Always commit in English."
        val s = section("Git Rules", body)
        val existing = block("Git Rules", body)
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.DUPLICATE, result.kind)
        assertEquals(existing, result.target)
        assertEquals(1.0, result.score, 0.001)
    }

    @Test
    fun `lightly edited text under same slug is SIMILAR`() {
        val s = section("Git Rules", "Always commit messages in English, not Russian.")
        val existing = block("Git Rules", "Always commit in English.")
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.SIMILAR, result.kind)
        assertEquals(existing, result.target)
        assertTrue(result.score in BlockMatcher.SIMILAR_THRESHOLD..<BlockMatcher.DUPLICATE_THRESHOLD,
            "score ${result.score} must land in the SIMILAR band")
    }

    @Test
    fun `same text but different slug is NEW`() {
        val body = "Be concise. No filler."
        val s = section("Communication", body)
        val existing = block("Style Guide", body)
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.NEW, result.kind)
        assertNull(result.target)
    }

    @Test
    fun `multiple same-slug blocks pick the highest score`() {
        val s = section("Git Rules", "Always commit in English.")
        val far = block("Git Rules", "Use four spaces for indentation.", id = "git-rules-far")
        val near = block("Git Rules", "Always commit in English.", id = "git-rules-near")
        val result = BlockMatcher.match(s, listOf(far, near))
        assertEquals(MatchKind.DUPLICATE, result.kind)
        assertEquals(near, result.target, "near must beat far on score")
    }

    @Test
    fun `score below SIMILAR threshold with matching slug is NEW`() {
        val s = section("Git Rules", "Indent four spaces always everywhere every time")
        val existing = block("Git Rules", "Commit messages in English language please")
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.NEW, result.kind, "coincidental name match must not be SIMILAR")
        assertNull(result.target, "target cleared when score is below SIMILAR band")
    }

    @Test
    fun `near-identical text under same slug is DUPLICATE`() {
        val s = section("Git Rules", "Always commit in English.\nNo Russian.")
        val existing = block("Git Rules", "Always commit in English.\nNo Russian.")
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.DUPLICATE, result.kind)
        assertTrue(result.score >= BlockMatcher.DUPLICATE_THRESHOLD,
            "score ${result.score} must reach DUPLICATE threshold")
    }

    @Test
    fun `partially overlapping text under same slug is SIMILAR`() {
        val s = section("Git Rules", "Always commit messages in English language.\nKeep them short.")
        val existing = block("Git Rules", "Always commit messages in English language.\nUse conventional commits.")
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.SIMILAR, result.kind)
        assertTrue(result.score in BlockMatcher.SIMILAR_THRESHOLD..<BlockMatcher.DUPLICATE_THRESHOLD,
            "score ${result.score} must land in the SIMILAR band")
    }

    @Test
    fun `empty body and empty content is NEW`() {
        val s = section("Empty", "")
        val existing = block("Empty", "")
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.NEW, result.kind)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun `normalized words strip markdown and punctuation`() {
        val words = BlockMatcher.normalizedWords("# Rules\n- Use **4-space** indent!")
        assertEquals(setOf("rules", "use", "4", "space", "indent"), words)
    }

    @Test
    fun `normalized words collapse case for matching`() {
        val s = section("Case", "Always Use ENGLISH Words")
        val existing = block("Case", "always use english words")
        val result = BlockMatcher.match(s, listOf(existing))
        assertEquals(MatchKind.DUPLICATE, result.kind)
    }
}
