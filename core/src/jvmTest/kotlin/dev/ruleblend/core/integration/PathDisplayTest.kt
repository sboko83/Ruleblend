package dev.ruleblend.core.integration

import java.nio.file.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PathDisplayTest {

    private val home = Path.of(System.getProperty("user.home")).resolve("path-display-user")
    private val separator = File.separator

    @Test
    fun `path inside home is shortened to tilde`() {
        assertEquals("~$separator.claude${separator}CLAUDE.md", PathDisplay.shorten(home.resolve(".claude/CLAUDE.md"), home))
        assertEquals("~${separator}Developer${separator}ruleblend", PathDisplay.shorten(home.resolve("Developer/ruleblend"), home))
    }

    @Test
    fun `home itself becomes a lone tilde`() {
        assertEquals("~", PathDisplay.shorten(home, home))
    }

    @Test
    fun `sibling whose name starts with home does not collapse`() {
        val sibling = home.resolveSibling("${home.fileName}2")
        assertEquals(sibling.toString(), PathDisplay.shorten(sibling, home))
        assertEquals(sibling.resolve("project").toString(), PathDisplay.shorten(sibling.resolve("project"), home))
    }

    @Test
    fun `path outside home is unchanged`() {
        val external = home.parent.resolve("external")
        assertEquals(external.toString(), PathDisplay.shorten(external, home))
    }

    @Test
    fun `redundant segments are normalized before shortening`() {
        assertEquals("~${separator}Developer${separator}ruleblend",
            PathDisplay.shorten(home.resolve("Developer/./ruleblend"), home.resolve("unused/..")))
    }

    @Test
    fun `home matching follows native filesystem capitalization rules`() {
        val assistant = Path.of(home.toString().uppercase()).resolve(".codex/AGENTS.md")
        val expected = if (File.separatorChar == '\\') "~\\.codex\\AGENTS.md" else assistant.toString()
        assertEquals(expected, PathDisplay.shorten(assistant, home))
    }
}
