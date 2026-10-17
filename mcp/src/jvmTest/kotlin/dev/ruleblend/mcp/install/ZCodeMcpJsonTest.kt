package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ZCodeMcpJsonTest {
    @Test
    fun `native edits preserve plugins MCP options and foreign server metadata`() {
        val original = """{"plugins":{"enabled":["sample"]},"mcp":{"timeout":42,"servers":{"foreign":{"url":"https://example.invalid","enable":false,"oauth":true}}},"theme":"dark"}"""
        val config = McpServerConfig.Stdio("/bin/echo", listOf("hello"), mapOf("A" to "B"))
        val updated = ZCodeMcpJson.upsert(original, "ours", McpServersJson.renderEntry(config))
        assertEquals(config, ZCodeMcpJson.read(updated, "ours")?.let(McpServersJson::parseEntry))
        assertEquals(listOf("foreign", "ours"), ZCodeMcpJson.names(updated))
        assertEquals(Json.parseToJsonElement(original), Json.parseToJsonElement(ZCodeMcpJson.remove(updated, "ours")))
        assertEquals(original, ZCodeMcpJson.remove(original, "absent"))
    }

    @Test
    fun `stdio and HTTP entries round trip through the native envelope`() {
        for (config in listOf(McpServerConfig.Stdio("npx"), McpServerConfig.Http("https://example.invalid", mapOf("X-Test" to "value")))) {
            val text = ZCodeMcpJson.upsert("", "server", McpServersJson.renderEntry(config))
            assertEquals(config, ZCodeMcpJson.read(text, "server")?.let(McpServersJson::parseEntry))
            assertEquals(setOf("mcp"), Json.parseToJsonElement(text).jsonObject.keys)
            assertNull(ZCodeMcpJson.read(ZCodeMcpJson.remove(text, "server"), "server"))
        }
    }

    @Test
    fun `malformed native sections are never replaced as empty`() {
        for (text in listOf("not-json", "[]", """{"mcp":null}""", """{"mcp":[]} """, """{"mcp":{"servers":false}}""")) {
            assertFailsWith<IllegalArgumentException>(text) {
                ZCodeMcpJson.upsert(text, "server", McpServersJson.renderEntry(McpServerConfig.Stdio("echo")))
            }
        }
    }
}
