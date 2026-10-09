package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SubagentTomlTest {
    @Test
    fun `basic and literal multiline strings preserve quotes comments and newlines`() {
        val basic = "developer_instructions = \"\"\"\r\nReview \"quoted\" code. # body\r\nKeep 'text'.\r\n\"\"\" # trailing\r\n"
        assertEquals("Review \"quoted\" code. # body\nKeep 'text'.\n", parseSubagentToml(basic)["developer_instructions"])
        val literal = "developer_instructions = '''\nKeep \\n and \"quotes\" literal.\n'''\nmodel = 'fixture'\n"
        assertEquals("Keep \\n and \"quotes\" literal.\n", parseSubagentToml(literal)["developer_instructions"])
        assertEquals("fixture", parseSubagentToml(literal)["model"])
    }

    @Test
    fun `multiline continuations trim whitespace and unicode escapes decode scalars`() {
        val source = "developer_instructions = \"\"\"\nReview \\  \n  \n  safely \\u0410 \\U0001F680\"\"\"\n"
        assertEquals("Review safely А 🚀", parseSubagentToml(source)["developer_instructions"])
    }

    @Test
    fun `instructions text table maps into portable content`() {
        val source = "name = 'reviewer'\nmodel = 'fixture'\n[instructions] # comment\ntext = '''Review.'''\n"
        val draft = CodexSubagentFormat.parse(source)
        assertEquals("Review.", draft?.content)
        assertEquals(mapOf("model" to "fixture"), draft?.fields)
    }

    @Test
    fun `multiline imported definition round trips through native renderer`() {
        val draft = CodexSubagentFormat.parse("name = 'reviewer'\ndescription = 'Reviews'\ncustom = 'kept'\ndeveloper_instructions = '''\nReview.\nCheck.\n'''\n")!!
        val block = Block(id = "reviewer", name = draft.name!!, description = draft.description!!, type = BlockType.SUBAGENT, content = draft.content)
        assertEquals(draft, CodexSubagentFormat.parse(CodexSubagentFormat.render(block, draft.toVariant())))
    }

    @Test
    fun `unsupported or malformed values never result in partial adoption`() {
        val invalid = listOf(
            "model = 42", "enabled = true", "tools = ['Read']", "nested = { field = 'value' }",
            "[unknown]\nfield = 'value'", "model = 'a'\nmodel = 'b'",
            "broken line", "model = \"bad\\q\"", "model = \"bad\\uD800\"", "model = \"bad\\U00110000\"",
            "model = \"unterminated", "model = '''unterminated", "model = 'ok' trailing",
            "[instructions]\ntext = 'duplicate'", "[instructions]\nunknown = 'value'",
        )
        invalid.forEach { tail ->
            val source = "developer_instructions = 'Body'\n$tail\n"
            assertNull(CodexSubagentFormat.parse(source), tail)
            assertFailsWith<Exception>(tail) { parseSubagentToml(source) }
        }
    }
}
