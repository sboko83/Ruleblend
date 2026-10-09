package dev.ruleblend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SlugTest {

    @Test
    fun lowercasesAndDashesSeparators() {
        assertEquals("git-no-commit", slugify("Git No Commit"))
    }

    @Test
    fun collapsesAndTrimsNonAlphanumerics() {
        assertEquals("swift-6-rules", slugify("  Swift 6 // rules!  "))
    }

    @Test
    fun returnsEmptyWhenNothingUsable() {
        assertEquals("", slugify("—///—"))
    }

    @Test
    fun uniqueIdReturnsBaseWhenFree() {
        assertEquals("git-push", uniqueId("git-push", listOf("swift-style")))
    }

    @Test
    fun uniqueIdAppendsNumericSuffixOnCollision() {
        val taken = listOf("git-push", "git-push-2")

        assertEquals("git-push-3", uniqueId("git-push", taken))
    }

    @Test
    fun uniqueIdTakesTheFirstFreeSuffixNotMaxPlusOne() {
        // -2 is free (only base is taken), so -2 wins even though no -2 exists yet.
        assertEquals("git-push-2", uniqueId("git-push", listOf("git-push")))
    }

    @Test
    fun uniqueIdUsesUntitledForEmptyBase() {
        assertEquals("untitled", uniqueId("", emptyList()))
        assertEquals("untitled-2", uniqueId("", listOf("untitled")))
    }

    @Test
    fun nextIdSlugifiesThenDeduplicates() {
        assertEquals("git-no-commit", nextId("Git No Commit", listOf("swift-style")))
        assertEquals("git-no-commit-2", nextId("Git No Commit", listOf("git-no-commit")))
    }
}
