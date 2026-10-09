package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexMcpTableTest {

    private val config = McpServerConfig.Stdio(
        command = "npx",
        args = listOf("-y", "@upstash/context7-mcp"),
        env = mapOf("CONTEXT7_API_KEY" to "key"),
    )
    private val httpConfig = McpServerConfig.Http(
        url = "https://mcp.example.com/mcp",
        headers = mapOf("Authorization" to "Bearer token", "X-Client-Name" to "Ruleblend"),
    )

    @Test
    fun `upsert into empty text`() {
        val text = CodexMcpTable.upsert("", "context7", config)
        assertTrue("[mcp_servers.context7]" in text)
        assertTrue("command = \"npx\"" in text)
        assertTrue("args = [\"-y\", \"@upstash/context7-mcp\"]" in text)
        assertTrue("env = { CONTEXT7_API_KEY = \"key\" }" in text)
    }

    @Test
    fun `upsert streamable HTTP config into empty text`() {
        val text = CodexMcpTable.upsert("", "remote", httpConfig)

        assertTrue("[mcp_servers.remote]" in text)
        assertTrue("url = \"https://mcp.example.com/mcp\"" in text)
        assertTrue("http_headers = { Authorization = \"Bearer token\", X-Client-Name = \"Ruleblend\" }" in text)
    }

    @Test
    fun `upsert appends after existing content preserving comments`() {
        val original = "# my codex config\nmodel = \"gpt-6\"\n\n[mcp_servers.other]\ncommand = \"/bin/other\"\n"
        val text = CodexMcpTable.upsert(original, "context7", config)
        assertTrue("# my codex config" in text)
        assertTrue("model = \"gpt-6\"" in text)
        assertTrue("[mcp_servers.other]" in text)
        assertTrue(text.indexOf("[mcp_servers.context7]") > text.indexOf("[mcp_servers.other]"))
    }

    @Test
    fun `upsert replaces its own section in place`() {
        val original = CodexMcpTable.upsert("", "context7", config) + "\n[extra]\nkey = 1\n"
        val updated = CodexMcpTable.upsert(original, "context7", config.copy(command = "bunx"))
        assertTrue("command = \"bunx\"" in updated)
        assertTrue("\"npx\"" !in updated)
        assertTrue("[extra]" in updated)
        assertEquals(1, Regex("\\[mcp_servers\\.context7]").findAll(updated).count())
    }

    @Test
    fun `remove drops the section and keeps the rest`() {
        val original = "model = \"gpt-6\"\n\n" +
            CodexMcpTable.upsert("", "context7", config) +
            "\n[extra]\nkey = 1\n"
        val text = CodexMcpTable.remove(original, "context7")
        assertNull(CodexMcpTable.read(text, "context7"))
        assertTrue("model = \"gpt-6\"" in text)
        assertTrue("[extra]" in text)
    }

    @Test
    fun `remove of a missing section returns text unchanged`() {
        val text = "model = \"gpt-6\"\n"
        assertEquals(text, CodexMcpTable.remove(text, "context7"))
    }

    @Test
    fun `read round trips what upsert wrote`() {
        val text = CodexMcpTable.upsert("", "context7", config)
        assertEquals(config, CodexMcpTable.read(text, "context7"))
        val bare = McpServerConfig.Stdio(command = "ctx7")
        assertEquals(bare, CodexMcpTable.read(CodexMcpTable.upsert("", "s", bare), "s"))
        assertEquals(httpConfig, CodexMcpTable.read(CodexMcpTable.upsert("", "remote", httpConfig), "remote"))
    }

    @Test
    fun `escaping round trips quotes and backslashes`() {
        val tricky = McpServerConfig.Stdio(
            command = """C:\Tools\mcp.exe""",
            args = listOf("""say "hi""""),
            env = mapOf("PATH_EXTRA" to """C:\bin"""),
        )
        val text = CodexMcpTable.upsert("", "tricky", tricky)
        assertEquals(tricky, CodexMcpTable.read(text, "tricky"))

        val httpTricky = McpServerConfig.Http(
            url = "https://mcp.example.com/\\\"quoted",
            headers = mapOf("X-Path" to "C:\\\\Tools\\\"client\""),
        )
        assertEquals(httpTricky, CodexMcpTable.read(CodexMcpTable.upsert("", "http-tricky", httpTricky), "http-tricky"))
    }

    @Test
    fun `names enumerate only MCP tables`() {
        val text = """
            model = "gpt-6"
            [mcp_servers.first]
            command = "one"
            [profiles.work]
            model = "gpt-6"
            [mcp_servers.second]
            url = "https://mcp.example.com"
        """.trimIndent()

        assertEquals(listOf("first", "second"), CodexMcpTable.names(text))
    }

    @Test
    fun `a servers sub-table is part of it and never a server of its own`() {
        val text = """
            [mcp_servers.first]
            command = "one"

            [mcp_servers.first.env]
            TOKEN = "secret"

            [mcp_servers.second]
            command = "two"
        """.trimIndent()

        assertEquals(listOf("first", "second"), CodexMcpTable.names(text))
        assertTrue("TOKEN" in CodexMcpTable.entryText(text, "first").orEmpty())
        assertEquals(McpServerConfig.Stdio(command = "one"), CodexMcpTable.read(text, "first"))
    }

    @Test
    fun `removing a server takes its sub-table and leaves the neighbour whole`() {
        val text = """
            [mcp_servers.first]
            command = "one"

            [mcp_servers.first.env]
            TOKEN = "secret"

            [mcp_servers.second]
            command = "two"
        """.trimIndent() + "\n"

        val updated = CodexMcpTable.remove(text, "first")
        assertTrue("mcp_servers.first" !in updated)
        assertTrue("TOKEN" !in updated)
        assertEquals(McpServerConfig.Stdio(command = "two"), CodexMcpTable.read(updated, "second"))
    }

    @Test
    fun `upsert replaces a hand-written sub-table instead of leaving it behind`() {
        val text = """
            [mcp_servers.context7]
            command = "old"

            [mcp_servers.context7.env]
            CONTEXT7_API_KEY = "stale"

            [extra]
            key = 1
        """.trimIndent() + "\n"

        val updated = CodexMcpTable.upsert(text, "context7", config)
        assertTrue("stale" !in updated)
        assertEquals(config, CodexMcpTable.read(updated, "context7"))
        assertTrue("[extra]" in updated)
    }
}
