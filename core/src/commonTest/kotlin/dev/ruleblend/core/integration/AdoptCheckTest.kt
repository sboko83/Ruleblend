package dev.ruleblend.core.integration

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdoptCheckTest {

    @Test
    fun `small text needs no warning`() {
        assertNull(adoptSizeWarning("a rule or two\nnothing more"))
    }

    @Test
    fun `empty text needs no warning`() {
        assertNull(adoptSizeWarning(""))
    }

    @Test
    fun `text at the line threshold needs no warning`() {
        assertNull(adoptSizeWarning(List(ADOPT_WARN_LINES) { "line" }.joinToString("\n")))
    }

    @Test
    fun `text above the line threshold warns`() {
        val warning = adoptSizeWarning(List(ADOPT_WARN_LINES + 1) { "line" }.joinToString("\n"))
        assertEquals(ADOPT_WARN_LINES + 1, warning?.lines)
    }

    @Test
    fun `few but huge lines warn on bytes`() {
        val text = "x".repeat(ADOPT_WARN_BYTES + 1)
        val warning = adoptSizeWarning(text)
        assertEquals(1, warning?.lines)
        assertEquals(ADOPT_WARN_BYTES + 1, warning?.bytes)
    }

    @Test
    fun `bytes are counted in utf-8, not characters`() {
        // Two bytes per Cyrillic character: under the cap by characters, over it by bytes.
        assertEquals(ADOPT_WARN_BYTES + 2, adoptSizeWarning("я".repeat(ADOPT_WARN_BYTES / 2 + 1))?.bytes)
    }

    // --- findImports ------------------------------------------------------------------------

    @Test
    fun `findImports picks up bare @path with extension`() {
        assertContentEquals(listOf("AGENTS.md"), findImports("See @AGENTS.md for the rules."))
    }

    @Test
    fun `findImports picks up @path with a slash`() {
        assertContentEquals(
            listOf("docs/rules.md", "../shared.md"),
            findImports("Read @docs/rules.md and @../shared.md."),
        )
    }

    @Test
    fun `findImports keeps every occurrence in file order`() {
        assertContentEquals(
            listOf("AGENTS.md", "AGENTS.md", "one.md"),
            findImports("@AGENTS.md at the top\ntext\n@AGENTS.md again\nand @one.md"),
        )
    }

    @Test
    fun `findImports ignores email addresses`() {
        assertTrue(findImports("Reach out to user@example.com if needed.").isEmpty())
    }

    @Test
    fun `findImports ignores annotations and decorators`() {
        // No slash, no extension → not a path.
        assertTrue(findImports("Use @Test and @Composable and @dagger").isEmpty())
    }

    @Test
    fun `findImports ignores @ mid-token`() {
        // A `@` inside a word is not a directive boundary.
        assertTrue(findImports("foo@bar.md").isEmpty())
    }

    @Test
    fun `findImports skips fenced code blocks`() {
        val text = """
            Live: @AGENTS.md

            ```
            Example: @sample.md
            ```

            Also live: @notes/plan.md
        """.trimIndent()
        assertContentEquals(listOf("AGENTS.md", "notes/plan.md"), findImports(text))
    }

    // --- findLocalLinkPaths -----------------------------------------------------------------

    @Test
    fun `findLocalLinkPaths returns relative paths`() {
        assertContentEquals(
            listOf("docs/spec.md", "img/screenshot.png"),
            findLocalLinkPaths("[spec](docs/spec.md) and ![shot](img/screenshot.png)"),
        )
    }

    @Test
    fun `findLocalLinkPaths ignores absolute URLs`() {
        val text = "[home](https://example.com) [mail](mailto:x@example.com) [inline](data:,foo)"
        assertTrue(findLocalLinkPaths(text).isEmpty())
    }

    @Test
    fun `findLocalLinkPaths ignores anchors and site-absolute paths`() {
        assertTrue(findLocalLinkPaths("[top](#anchor) [root](/etc/passwd)").isEmpty())
    }

    @Test
    fun `findLocalLinkPaths strips fragment and query`() {
        assertContentEquals(
            listOf("doc.md", "img.png"),
            findLocalLinkPaths("[a](doc.md#part) ![b](img.png?raw=true)"),
        )
    }

    @Test
    fun `findLocalLinkPaths skips fenced code blocks`() {
        val text = """
            [real](docs/live.md)

            ```md
            [sample](sample/only.md)
            ```
        """.trimIndent()
        assertContentEquals(listOf("docs/live.md"), findLocalLinkPaths(text))
    }
}
