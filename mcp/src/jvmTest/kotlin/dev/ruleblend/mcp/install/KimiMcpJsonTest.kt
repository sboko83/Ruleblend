package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KimiMcpJsonTest {
    @Test
    fun `Kimi entries infer stdio and http transports without a type field`() {
        val stdio = McpServerConfig.Stdio("npx", listOf("-y", "server"), mapOf("TOKEN" to "token"))
        val http = McpServerConfig.Http("https://mcp.example.com/mcp", mapOf("X-Client" to "Ruleblend"))
        var text = KimiMcpJson.upsert("", "stdio", stdio)
        text = KimiMcpJson.upsert(text, "remote", http)

        assertFalse("\"type\"" in text)
        assertTrue("\"command\"" in text)
        assertTrue("\"url\"" in text)
        assertEquals(stdio, KimiMcpJson.read(text, "stdio"))
        assertEquals(http, KimiMcpJson.read(text, "remote"))
    }

    @Test
    fun `Kimi table preserves foreign keys and servers`() {
        val original = """{"custom":true,"mcpServers":{"other":{"command":"other"}}}"""
        val text = KimiMcpJson.upsert(original, "mine", McpServerConfig.Stdio("mine"))

        assertTrue("\"custom\"" in text)
        assertEquals(McpServerConfig.Stdio("other"), KimiMcpJson.read(text, "other"))
        assertEquals(McpServerConfig.Stdio("mine"), KimiMcpJson.read(text, "mine"))
    }
}
