package dev.ruleblend.core.storage

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockFileTest {

    private val block = Block(
        id = "git-no-commit",
        name = "Git: no commit",
        description = "Never commit without asking",
        version = 3,
        type = BlockType.RULE,
        content = "# Git\n\n- Do not commit unless asked.\n---\ntrailing delimiter in body\n",
    )

    @Test
    fun `round trip preserves block`() {
        val favorite = block.copy(favorite = true)
        val parsed = BlockFile.parse(favorite.id, BlockFile.serialize(favorite))
        assertEquals(favorite, parsed)
    }

    @Test
    fun `old frontmatter defaults favorite to false`() {
        val text = "---\nname: Legacy\n---\n\nbody"
        assertEquals(false, BlockFile.parse("legacy", text).favorite)
    }

    @Test
    fun `parses crlf and empty content`() {
        val empty = block.copy(content = "")
        val text = BlockFile.serialize(empty).replace("\n", "\r\n")
        assertEquals(empty, BlockFile.parse(empty.id, text))
    }

    @Test
    fun `rejects file without frontmatter`() {
        assertFailsWith<IllegalArgumentException> { BlockFile.parse("x", "# just markdown") }
    }

    @Test
    fun `rejects file whose frontmatter never closes in bounds`() {
        val runaway = "---\n" + "name: x\n".repeat(500) + "---\n\nbody\n"
        assertFailsWith<IllegalArgumentException> { BlockFile.parse("x", runaway) }
    }

    @Test
    fun `body containing a ruleblend marker string is inert text`() {
        val withMarker = block.copy(content = "<!-- ruleblend:begin id=nested v=1 hash=x -->\nbody\n<!-- ruleblend:end id=nested -->\n")
        val parsed = BlockFile.parse(withMarker.id, BlockFile.serialize(withMarker))
        assertEquals(withMarker, parsed)
    }

    @Test
    fun `mcp block round trip`() {
        val mcp = Block(
            id = "context7",
            name = "Context7",
            version = 2,
            type = BlockType.MCP,
            content = "{\n  \"transport\": \"stdio\",\n  \"command\": \"npx\"\n}",
        )
        assertEquals(mcp, BlockFile.parse(mcp.id, BlockFile.serialize(mcp)))
    }

    @Test
    fun `subagent round trip preserves its per assistant fields`() {
        val subagent = Block(
            id = "code-reviewer",
            name = "Code reviewer",
            description = "Reviews changes before merge",
            version = 2,
            type = BlockType.SUBAGENT,
            variants = mapOf(
                "claude-code" to SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read")),
                "codex" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
            ),
            content = "# Review\n\nFind correctness and regression risks.\n",
        )

        val serialized = BlockFile.serialize(subagent)

        assertTrue(serialized.contains("subagent"))
        assertTrue(serialized.contains("gpt-5.6-terra"))
        assertEquals(subagent, BlockFile.parse(subagent.id, serialized))
    }

    @Test
    fun `a pre-variant model preference migrates onto the assistants that could read it`() {
        val parsed = BlockFile.parse(
            "legacy",
            "---\nname: Reviewer\ntype: subagent\nmodel: gpt-5.6-terra\n---\n\n# Review\n",
        )

        assertEquals(
            mapOf(
                "claude-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
                "codex" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
                "kimi-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
            ),
            parsed.variants,
        )
        // Re-reading what was written back keeps the variants, and drops the pre-variant key.
        assertEquals(parsed, BlockFile.parse(parsed.id, BlockFile.serialize(parsed)))
    }

    @Test
    fun `a pre-variant model on a rule is not turned into subagent variants`() {
        val parsed = BlockFile.parse("legacy", "---\nname: Rule\nmodel: gpt-5.6-terra\n---\n\nbody\n")

        assertEquals(emptyMap(), parsed.variants)
    }

    @Test
    fun `body containing a run marker string is inert text`() {
        val withMarker = block.copy(content = "<!-- kb nested v1 deadbeef -->\nbody\n<!-- kb:end -->\n")
        val parsed = BlockFile.parse(withMarker.id, BlockFile.serialize(withMarker))
        assertEquals(withMarker, parsed)
    }

    @Test
    fun `round trip preserves heading and its level`() {
        val titled = block.copy(heading = "Verification budget", headingLevel = 3)
        val serialized = BlockFile.serialize(titled)

        assertTrue(serialized.contains("heading: \"Verification budget\""))
        assertEquals(titled, BlockFile.parse(titled.id, serialized))
    }

    @Test
    fun `old frontmatter defaults to no heading`() {
        val parsed = BlockFile.parse("legacy", "---\nname: Legacy\n---\n\nbody")

        assertEquals("", parsed.heading)
        assertEquals(2, parsed.headingLevel)
    }
}
