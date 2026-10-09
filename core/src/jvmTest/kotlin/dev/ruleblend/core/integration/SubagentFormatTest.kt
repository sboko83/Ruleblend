package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.SubagentVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SubagentFormatTest {

    private val subagent = Block(
        id = "reviewer",
        name = "Code \"reviewer\"",
        description = "Reviews Kotlin\nchanges",
        type = BlockType.SUBAGENT,
        variants = mapOf(
            "claude-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
            "codex" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
            "kimi-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
        ),
        content = "# Review\n\nCheck \\ paths.\n",
    )

    private val claudeVariant = subagent.variants.getValue("claude-code")

    @Test
    fun `Claude Code renders Markdown frontmatter with model`() {
        assertEquals(
            """
            ---
            name: "Code \"reviewer\""
            description: "Reviews Kotlin\nchanges"
            model: "gpt-5.6-terra"
            ---

            # Review

            Check \ paths.
            """.trimIndent() + "\n",
            ClaudeCodeSubagentFormat.render(subagent, claudeVariant),
        )
    }

    @Test
    fun `Kimi Code calls model preference by its native key`() {
        assertEquals(
            """
            ---
            name: "Code \"reviewer\""
            description: "Reviews Kotlin\nchanges"
            modelPreference: "gpt-5.6-terra"
            ---

            # Review

            Check \ paths.
            """.trimIndent() + "\n",
            KimiCodeSubagentFormat.render(subagent, subagent.variants.getValue("kimi-code")),
        )
    }

    @Test
    fun `Codex renders escaped TOML developer instructions`() {
        assertEquals(
            """
            name = "Code \"reviewer\""
            description = "Reviews Kotlin\nchanges"
            model = "gpt-5.6-terra"
            developer_instructions = "# Review\n\nCheck \\ paths.\n"
            """.trimIndent() + "\n",
            CodexSubagentFormat.render(subagent, subagent.variants.getValue("codex")),
        )
    }

    @Test
    fun `an assistant without a variant is rendered without its tunable fields`() {
        val claudeOnly = subagent.copy(variants = mapOf("claude-code" to claudeVariant))

        assertEquals(
            """
            name = "Code \"reviewer\""
            description = "Reviews Kotlin\nchanges"
            developer_instructions = "# Review\n\nCheck \\ paths.\n"
            """.trimIndent() + "\n",
            CodexSubagentFormat.render(claudeOnly, claudeOnly.variants["codex"] ?: SubagentVariant.EMPTY),
        )
    }

    @Test
    fun `Claude Code writes its declared fields in order, then the ones it does not model`() {
        val variant = SubagentVariant(
            linkedMapOf(
                "isolation" to "worktree",
                "color" to "cyan",
                "tools" to "Bash, Read",
                "model" to "haiku",
            ),
        )

        assertEquals(
            """
            ---
            name: "Code \"reviewer\""
            description: "Reviews Kotlin\nchanges"
            model: "haiku"
            tools: "Bash, Read"
            color: "cyan"
            isolation: "worktree"
            ---

            # Review

            Check \ paths.
            """.trimIndent() + "\n",
            ClaudeCodeSubagentFormat.render(subagent, variant),
        )
    }

    @Test
    fun `formats parse their own render`() {
        val expected = SubagentDraft(
            name = subagent.name,
            description = subagent.description,
            fields = mapOf("model" to "gpt-5.6-terra"),
            content = subagent.content,
        )

        assertEquals(expected, ClaudeCodeSubagentFormat.parse(ClaudeCodeSubagentFormat.render(subagent, claudeVariant)))
        assertEquals(expected, KimiCodeSubagentFormat.parse(KimiCodeSubagentFormat.render(subagent, claudeVariant)))
        assertEquals(expected, CodexSubagentFormat.parse(CodexSubagentFormat.render(subagent, claudeVariant)))
    }

    @Test
    fun `a parsed field keeps the canonical id its format gives it`() {
        val kimi = KimiCodeSubagentFormat.parse(
            "---\nname: Reviewer\nmodelPreference: kimi-k2\n---\n\nbody\n",
        )

        assertEquals(mapOf("model" to "kimi-k2"), kimi?.fields)
        assertEquals("kimi-k2", kimi?.model)
    }

    @Test
    fun `a field the format does not model survives under its native name`() {
        val parsed = ClaudeCodeSubagentFormat.parse(
            """
            ---
            name: Reviewer
            description: Reviews changes
            model: haiku
            tools:
              - Bash
              - Read
            disable-model-invocation: "true"
            ---

            body
            """.trimIndent() + "\n",
        )

        assertEquals(
            mapOf("model" to "haiku", "tools" to "Bash, Read", "disable-model-invocation" to "true"),
            parsed?.fields,
        )
    }

    @Test
    fun `a nested field is left to the file rather than half adopted`() {
        val parsed = ClaudeCodeSubagentFormat.parse(
            "---\nname: Reviewer\npermissions:\n  edit: ask\nmodel: haiku\n---\n\nbody\n",
        )

        assertEquals(mapOf("model" to "haiku"), parsed?.fields)
    }

    @Test
    fun `Markdown parser accepts a header without optional fields`() {
        assertEquals(
            SubagentDraft(content = "# Instructions\n"),
            ClaudeCodeSubagentFormat.parse("---\n---\n\n# Instructions\n"),
        )
    }

    @Test
    fun `formats reject content outside their definition syntax`() {
        assertNull(ClaudeCodeSubagentFormat.parse("# No frontmatter\n"))
        assertNull(CodexSubagentFormat.parse("name = \"Reviewer\"\n"))
        assertNull(CodexSubagentFormat.parse("developer_instructions = \"unterminated\n"))
    }

    @Test
    fun `formats omit an absent optional model`() {
        assertEquals(
            """
            ---
            name: "Code \"reviewer\""
            description: "Reviews Kotlin\nchanges"
            ---

            # Review

            Check \ paths.
            """.trimIndent() + "\n",
            ClaudeCodeSubagentFormat.render(subagent),
        )
        assertEquals(
            """
            name = "Code \"reviewer\""
            description = "Reviews Kotlin\nchanges"
            developer_instructions = "# Review\n\nCheck \\ paths.\n"
            """.trimIndent() + "\n",
            CodexSubagentFormat.render(subagent),
        )
    }

    @Test
    fun `formats reject a non subagent block`() {
        assertFailsWith<IllegalArgumentException> {
            ClaudeCodeSubagentFormat.render(subagent.copy(type = BlockType.RULE), claudeVariant)
        }
    }
}
