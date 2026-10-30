package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeMcpJsonTest {
    @Test
    fun `native edits keep mcp timeout foreign servers and every other root key`() {
        val original = """{"${'$'}schema":"https://opencode.ai/config.json","theme":"dark","mcp":{"timeout":5000,"servers":{"foreign":{"type":"local","command":["node","server.js"]},"off":{"type":"remote","url":"https://example.invalid","disabled":true}}}}"""
        val config = McpServerConfig.Stdio("/bin/echo", listOf("hello"), mapOf("A" to "B"))

        val updated = OpenCodeMcpJson.upsert(original, "ours", OpenCodeMcpJson.renderEntry(config))

        assertEquals(config, OpenCodeMcpJson.read(updated, "ours")?.let(OpenCodeMcpJson::parseEntry))
        assertEquals(listOf("foreign", "off", "ours"), OpenCodeMcpJson.names(updated))
        assertTrue("\"timeout\": 5000" in updated)
        assertTrue("\"theme\": \"dark\"" in updated)
        assertEquals(
            McpServerConfig.Stdio("node", listOf("server.js")),
            OpenCodeMcpJson.read(updated, "foreign")?.let(OpenCodeMcpJson::parseEntry),
        )
        assertEquals(Json.parseToJsonElement(original), Json.parseToJsonElement(OpenCodeMcpJson.remove(updated, "ours")))
        assertEquals(original, OpenCodeMcpJson.remove(original, "absent"))
    }

    @Test
    fun `local and remote entries round trip through the native envelope`() {
        val stdio = McpServerConfig.Stdio("npx", listOf("-y", "ctx7"), mapOf("TOKEN" to "value"))
        val remote = McpServerConfig.Http("https://mcp.example.com/mcp", mapOf("X-Client" to "Ruleblend"))

        val local = OpenCodeMcpJson.upsert("", "stdio", OpenCodeMcpJson.renderEntry(stdio))
        val both = OpenCodeMcpJson.upsert(local, "remote", OpenCodeMcpJson.renderEntry(remote))

        assertTrue("\"type\": \"local\"" in local)
        assertEquals(
            listOf("npx", "-y", "ctx7"),
            (OpenCodeMcpJson.read(local, "stdio")?.get("command") as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals(setOf("mcp"), Json.parseToJsonElement(both).jsonObject.keys)
        assertEquals(stdio, OpenCodeMcpJson.read(both, "stdio")?.let(OpenCodeMcpJson::parseEntry))
        assertEquals(remote, OpenCodeMcpJson.read(both, "remote")?.let(OpenCodeMcpJson::parseEntry))
        assertFalse("enabled" in both)
        assertFalse("disabled" in both)
        assertNull(OpenCodeMcpJson.read(OpenCodeMcpJson.remove(both, "remote"), "remote"))
        assertEquals(stdio, OpenCodeMcpJson.read(both, "stdio")?.let(OpenCodeMcpJson::parseEntry))
    }

    @Test
    fun `a v1 flat command string is not read as a native local entry`() {
        val v1 = """{"mcp":{"context7":{"command":"npx","args":["-y","ctx7"]}}}"""

        assertEquals(emptyList(), OpenCodeMcpJson.names(v1))
        assertNull(OpenCodeMcpJson.read(v1, "context7"))
    }

    @Test
    fun `malformed native sections are never replaced as empty`() {
        for (text in listOf("not-json", "[]", """{"mcp":null}""", """{"mcp":[]}""", """{"mcp":{"servers":false}}""")) {
            assertFailsWith<IllegalArgumentException>(text) {
                OpenCodeMcpJson.upsert(text, "server", OpenCodeMcpJson.renderEntry(McpServerConfig.Stdio("echo")))
            }
        }
    }
}
