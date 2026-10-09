package dev.ruleblend.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownSegmenterTest {

    private val ruleWithEverything = """
        ---
        name: "sample"
        version: 2
        ---

        # Global Rules

        ## Verification

        "Done" means: ran the check, saw the output, output confirms.
        No "should work" — run `./gradlew :core:jvmTest` first.

        - Use modern Kotlin Multiplatform conventions, see [the docs](https://kotlinlang.org/docs).
        - Never pass `--dangerously-skip-permissions` to the agent.
        - [ ] Unchecked item with a path /Users/example/Developer/ruleblend/core.

        <!-- rb1 id="x" version="2" -->
        Managed content lives here.
        <!-- rb:end -->

        ```kotlin
        // A fence must never reach the translator.
        fun main() = println("- not a list item")
        ```

        | column | value |
        |--------|-------|
        | a      | b     |

        > A quoted line that is prose.

            indented code block
            second line

        Trailing paragraph without a newline at the end.
    """.trimIndent()

    @Test
    fun `segmentation is lossless`() {
        val document = TranslationDocument.of(ruleWithEverything)
        assertEquals(ruleWithEverything, document.source())
    }

    @Test
    fun `echoing every request rebuilds the original document`() {
        val document = TranslationDocument.of(ruleWithEverything)
        assertEquals(ruleWithEverything, document.assemble(document.requests))
    }

    @Test
    fun `structure never reaches the translator`() {
        val requests = TranslationDocument.of(ruleWithEverything).requests.joinToString("\n")

        assertFalse(requests.contains("```"), "code fence leaked")
        assertFalse(requests.contains("fun main"), "code body leaked")
        assertFalse(requests.contains("rb1"), "Ruleblend marker leaked")
        assertFalse(requests.contains("| column"), "table leaked")
        assertFalse(requests.contains("indented code block"), "indented code leaked")
        assertFalse(requests.contains("name: \"sample\""), "frontmatter leaked")
        assertFalse(requests.contains("--dangerously-skip-permissions"), "flag leaked")
        assertFalse(requests.contains("/Users/example"), "path leaked")
        assertFalse(requests.contains("https://"), "url leaked")

        assertTrue(requests.contains("Global Rules"), "heading text should be translatable")
        assertTrue(requests.contains("Use modern Kotlin Multiplatform conventions"), "list text should be translatable")
        assertTrue(requests.contains("the docs"), "link text should stay translatable")
        assertTrue(requests.contains("A quoted line that is prose."), "quote text should be translatable")
    }

    @Test
    fun `markdown scaffolding stays out of the request but returns on assembly`() {
        val source = "## Title\n\n- item one\n1. item two\n> quoted\n"
        val document = TranslationDocument.of(source)

        assertEquals(listOf("Title", "item one", "item two", "quoted"), document.requests)
        assertEquals(source, document.assemble(document.requests))
    }

    @Test
    fun `translated text keeps its markers`() {
        val source = "Run `./gradlew build` before pushing.\n"
        val document = TranslationDocument.of(source)
        val request = document.requests.single()

        // The translator sees a placeholder in place of the command, and moves it around freely.
        val translated = request.replace("Run ", "Запусти ").replace(" before pushing.", " перед пушем.")

        assertEquals("Запусти `./gradlew build` перед пушем.\n", document.assemble(listOf(translated)))
    }

    @Test
    fun `a lost placeholder falls back to the original segment`() {
        val source = "Never run `rm -rf` here.\n"
        val document = TranslationDocument.of(source)

        assertEquals(source, document.assemble(listOf("Никогда не запускай здесь.")))
    }

    @Test
    fun `a duplicated placeholder falls back to the original segment`() {
        val source = "Never run `rm -rf` here.\n"
        val document = TranslationDocument.of(source)
        val doubled = document.requests.single().let { it + " " + it }

        assertEquals(source, document.assemble(listOf(doubled)))
    }

    @Test
    fun `a hand-wrapped paragraph is sent as one line`() {
        val source = "First line of one thought,\nsecond line of the same thought.\n\nA separate one.\n"
        val document = TranslationDocument.of(source)

        assertEquals(2, document.requests.size)
        // The translator answers a line break with a blank line, so it never sees one.
        assertEquals("First line of one thought, second line of the same thought.", document.requests.first())
    }

    @Test
    fun `line breaks invented by the translator do not survive`() {
        val source = "First line of one thought,\nsecond line of the same thought.\n"
        val document = TranslationDocument.of(source)
        val withBlankLine = "Первая строка одной мысли,\n\nвторая строка той же мысли."

        val assembled = document.assemble(listOf(withBlankLine))

        assertFalse(assembled.contains("\n\n"), "a paragraph must not gain blank lines: $assembled")
        assertTrue(assembled.endsWith("\n"), "the segment keeps its own line ending")
    }

    @Test
    fun `a translated paragraph is wrapped the way the original was`() {
        val original = buildString {
            append("Docs record decisions, not diffs and not the code. Update the affected doc in\n")
            append("the same change; a behavior-neutral refactor needs no doc change at all.\n")
        }
        val width = original.trimEnd('\n').split("\n").maxOf { it.length }
        val document = TranslationDocument.of(original)

        val translated = document.assemble(
            listOf(
                "Документация фиксирует решения, а не диффы и не код. Обновляйте затронутый документ " +
                    "в том же изменении; поведенчески нейтральный рефакторинг не требует правок вовсе.",
            )
        )

        val lines = translated.trimEnd('\n').split("\n")
        assertTrue(lines.size > 1, "a wrapped original should stay wrapped: $translated")
        assertTrue(lines.all { it.length <= width }, "no line may exceed the original width $width: $lines")
    }

    @Test
    fun `a single-line paragraph stays on one line`() {
        val source = "One short line that was never wrapped.\n"
        val document = TranslationDocument.of(source)

        val assembled = document.assemble(listOf("Одна короткая строка, которую никогда не переносили и она довольно длинная."))

        assertEquals("Одна короткая строка, которую никогда не переносили и она довольно длинная.\n", assembled)
    }

    @Test
    fun `documents without prose produce no requests`() {
        val source = "```\ncode only\n```\n"
        val document = TranslationDocument.of(source)

        assertTrue(document.requests.isEmpty())
        assertEquals(source, document.assemble(emptyList()))
    }

    @Test
    fun `empty input is handled`() {
        val document = TranslationDocument.of("")
        assertTrue(document.requests.isEmpty())
        assertEquals("", document.assemble(emptyList()))
    }
}
