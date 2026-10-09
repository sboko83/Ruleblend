package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SectionCandidateTest {

    @Test
    fun `untitled section deduplicates against the library via its first-line slug`() {
        // Re-importing a file whose untitled prefix was already adopted must not offer it again.
        val text = "Always answer in Russian.\nBe concise.\n\n# Rules\nrule body."
        val sections = SectionExtractor.extract(text)
        val adopted = Block(
            id = "always-answer-in-russian",
            name = "Always answer in Russian",
            content = sections[0].body,
        )

        val candidate = SectionCandidate.initial(sections[0], listOf(adopted))

        assertEquals(MatchKind.DUPLICATE, candidate.match.kind)
        assertFalse(candidate.included, "an already-adopted prefix must not be re-offered")
        assertEquals(SectionAction.SKIP, candidate.action)
    }

    @Test
    fun `non-offerable section is a fixed skip`() {
        val text = "<!-- GENERATED banner -->\n\n# Rules\nrule body."
        val sections = SectionExtractor.extract(text)
        assertFalse(sections[0].offerable)

        val candidate = SectionCandidate.initial(sections[0], emptyList())

        assertFalse(candidate.included)
        assertEquals(SectionAction.SKIP, candidate.action)
        assertEquals(MatchKind.NEW, candidate.match.kind)
    }
}
