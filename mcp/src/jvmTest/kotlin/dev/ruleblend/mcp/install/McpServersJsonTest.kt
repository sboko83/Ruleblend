package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpServersJsonTest {

    private val stdio = McpServerConfig.Stdio(
        command = "npx",
        args = listOf("-y", "@upstash/context7-mcp"),
        env = mapOf("CONTEXT7_API_KEY" to "key"),
    )

    private val http = McpServerConfig.Http(
        url = "https://mcp.context7.com/mcp",
        headers = mapOf("CONTEXT7_API_KEY" to "key"),
    )

    @Test
    fun `upsert into empty text creates the structure`() {
        val text = McpServersJson.upsert("", "context7", McpServersJson.renderEntry(stdio))
        val entry = McpServersJson.read(text, "context7")!!
        assertEquals("stdio", entry["type"]?.jsonPrimitive?.content)
        assertEquals("npx", entry["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `upsert preserves foreign keys and other servers`() {
        val original = """{"numStartups":42,"mcpServers":{"other":{"type":"stdio","command":"/bin/other"}}}"""
        val text = McpServersJson.upsert(original, "context7", McpServersJson.renderEntry(http))
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("42", root["numStartups"]?.jsonPrimitive?.content)
        assertTrue("other" in root["mcpServers"]!!.jsonObject)
        assertTrue("context7" in root["mcpServers"]!!.jsonObject)
    }

    @Test
    fun `upsert replaces an existing entry`() {
        val first = McpServersJson.upsert("", "context7", McpServersJson.renderEntry(stdio))
        val second = McpServersJson.upsert(first, "context7", McpServersJson.renderEntry(http))
        assertEquals(http, McpServersJson.parseEntry(McpServersJson.read(second, "context7")!!))
    }

    @Test
    fun `remove drops only the named entry`() {
        var text = McpServersJson.upsert("", "context7", McpServersJson.renderEntry(stdio))
        text = McpServersJson.upsert(text, "other", McpServersJson.renderEntry(http))
        text = McpServersJson.remove(text, "context7")
        assertNull(McpServersJson.read(text, "context7"))
        assertEquals(http, McpServersJson.parseEntry(McpServersJson.read(text, "other")!!))
    }

    @Test
    fun `remove of a missing entry returns text unchanged`() {
        val text = """{"mcpServers":{}}"""
        assertEquals(text, McpServersJson.remove(text, "context7"))
        assertEquals("", McpServersJson.remove("", "context7"))
    }

    @Test
    fun `render and parse round trip both transports`() {
        assertEquals(stdio, McpServersJson.parseEntry(McpServersJson.renderEntry(stdio)))
        assertEquals(http, McpServersJson.parseEntry(McpServersJson.renderEntry(http)))
        val bare = McpServerConfig.Stdio(command = "ctx7")
        assertEquals(bare, McpServersJson.parseEntry(McpServersJson.renderEntry(bare)))
    }

    @Test
    fun `parseEntry reads a typeless stdio entry`() {
        val entry = Json.parseToJsonElement("""{"command":"npx","args":["-y"]}""").jsonObject
        assertEquals(McpServerConfig.Stdio(command = "npx", args = listOf("-y")), McpServersJson.parseEntry(entry))
    }

    @Test
    fun `parseEntry returns null for unknown shapes`() {
        assertNull(McpServersJson.parseEntry(Json.parseToJsonElement("""{"type":"ws","url":"wss://x"}""").jsonObject))
        assertNull(McpServersJson.parseEntry(Json.parseToJsonElement("""{"type":"stdio"}""").jsonObject))
    }

    @Test
    fun `names enumerate only the top-level MCP object and ignore malformed JSON`() {
        val text = """{"mcpServers":{"first":{},"second":{}},"projects":{"repo":{"mcpServers":{"local":{}}}}}"""

        assertEquals(listOf("first", "second"), McpServersJson.names(text))
        assertEquals(emptyList(), McpServersJson.names("{ broken"))
    }
}
