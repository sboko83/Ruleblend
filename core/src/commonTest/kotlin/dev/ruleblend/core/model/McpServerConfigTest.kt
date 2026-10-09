package dev.ruleblend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerConfigTest {

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
    fun `stdio round trip`() {
        assertEquals(stdio, McpConfigCodec.parse(McpConfigCodec.serialize(stdio)).getOrThrow())
    }

    @Test
    fun `http round trip`() {
        assertEquals(http, McpConfigCodec.parse(McpConfigCodec.serialize(http)).getOrThrow())
    }

    @Test
    fun `serialization is byte-stable`() {
        val body = McpConfigCodec.serialize(stdio)
        val again = McpConfigCodec.serialize(McpConfigCodec.parse(body).getOrThrow())
        assertEquals(body, again)
    }

    @Test
    fun `parses minimal stdio with defaults`() {
        val parsed = McpConfigCodec.parse("""{"transport": "stdio", "command": "ctx7"}""").getOrThrow()
        assertEquals(McpServerConfig.Stdio(command = "ctx7"), parsed)
    }

    @Test
    fun `ignores unknown keys`() {
        val parsed = McpConfigCodec.parse(
            """{"transport": "http", "url": "https://x.dev/mcp", "timeout": 5}""",
        ).getOrThrow()
        assertEquals(McpServerConfig.Http(url = "https://x.dev/mcp"), parsed)
    }

    @Test
    fun `malformed body fails`() {
        assertTrue(McpConfigCodec.parse("not json").isFailure)
        assertTrue(McpConfigCodec.parse("""{"transport": "carrier-pigeon"}""").isFailure)
        assertTrue(McpConfigCodec.parse("").isFailure)
    }

    @Test
    fun `validation matrix`() {
        assertEquals(emptyList(), stdio.validationErrors())
        assertEquals(emptyList(), http.validationErrors())
        assertEquals(listOf(McpConfigError.COMMAND_REQUIRED), McpServerConfig.Stdio(command = " ").validationErrors())
        assertEquals(listOf(McpConfigError.URL_INVALID), McpServerConfig.Http(url = "ftp://x").validationErrors())
        assertEquals(
            listOf(McpConfigError.BLANK_KEY),
            stdio.copy(env = mapOf("" to "v")).validationErrors(),
        )
        assertEquals(
            listOf(McpConfigError.BLANK_KEY),
            http.copy(headers = mapOf(" " to "v")).validationErrors(),
        )
    }
}
