package dev.ruleblend.core.compare

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals

class CompareCandidatesTest {
    @Test fun putsFiveMostSimilarCandidatesFirstAndKeepsTheRestInCatalogOrder() {
        val source = block("source", "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda")
        val catalog = listOf(
            block("first-rest", "unrelated words only"),
            block("first", source.content),
            block("second", "alpha beta gamma delta epsilon zeta eta theta iota kappa"),
            block("third", "alpha beta gamma delta epsilon zeta eta theta iota"),
            block("fourth", "alpha beta gamma delta epsilon zeta eta theta"),
            block("fifth", "alpha beta gamma delta epsilon zeta eta"),
            block("second-rest", "nothing in common here"),
        )

        val result = CompareCandidates(source, catalog)

        assertEquals(listOf("first", "second", "third", "fourth", "fifth"), result.top.map { it.block.id })
        assertEquals(100, result.top.first().percent)
        assertEquals(listOf("first-rest", "second-rest"), result.remaining.map { it.block.id })
    }

    @Test fun keepsCatalogOrderForEqualScores() {
        val source = block("source", "alpha beta gamma")
        val catalog = listOf(
            block("first", "alpha beta"),
            block("second", "alpha beta"),
        )

        val result = CompareCandidates(source, catalog)

        assertEquals(listOf("first", "second"), result.top.map { it.block.id })
        assertEquals(emptyList(), result.remaining)
    }

    private fun block(id: String, content: String) = Block(id = id, name = id, content = content)
}
