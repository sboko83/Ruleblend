package dev.ruleblend.core.compare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarityTest {
    @Test fun identicalTextIsOneHundredPercentSimilar() {
        val score = Similarity.between("Use concise Kotlin code.", "Use concise Kotlin code.")

        assertEquals(1.0, score.wordJaccard)
        assertEquals(1.0, score.trigramJaccard)
        assertEquals(100, score.percent)
    }

    @Test fun combinesWordAndCharacterEvidence() {
        val score = Similarity.between("alpha beta gamma", "alpha beta delta")

        assertEquals(0.5, score.wordJaccard)
        assertTrue(score.trigramJaccard > 0.0)
        assertTrue(score.value in 0.25..1.0)
    }

    @Test fun considersTwoEmptyBodiesTheSame() {
        assertEquals(100, Similarity.between("", "").percent)
    }
}
