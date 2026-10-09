package dev.ruleblend.mcp

import java.nio.file.Files
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.mcp.install.CodexMcpTable
import dev.ruleblend.mcp.install.KimiMcpJson
import dev.ruleblend.mcp.install.McpServersJson
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpConnectorTest {

    private lateinit var home: Path
    private val binary: Path get() = Path.of("/Applications/Ruleblend.app/Contents/MacOS/Ruleblend")
    private val movedBinary: Path get() = Path.of("/Somewhere/Else/Ruleblend")

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-connector-home")
        home.resolve(".claude").createDirectories()
        home.resolve(".codex").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    // --- Claude Code ---

    @Test
    fun `claude registers into a missing config file`() {
        val connector = ClaudeCodeConnector(home)
        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))

        connector.register(binary)

        assertEquals(McpRegistration.REGISTERED, connector.status(binary))
        val root = Json.parseToJsonElement(home.resolve(".claude.json").readText()).jsonObject
        val entry = root["mcpServers"]!!.jsonObject["ruleblend"]!!.jsonObject
        assertEquals("stdio", entry["type"]?.jsonPrimitive?.content)
        assertEquals(binary.toString(), entry["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun `claude preserves unrelated keys and other servers`() {
        home.resolve(".claude.json").writeText(
            """{"numStartups":42,"mcpServers":{"other":{"type":"stdio","command":"/bin/other"}},"projects":{"/a":{"x":1}}}""",
        )
        val connector = ClaudeCodeConnector(home)

        connector.register(binary)

        val root = Json.parseToJsonElement(home.resolve(".claude.json").readText()).jsonObject
        assertEquals("42", root["numStartups"]?.jsonPrimitive?.content)
        assertEquals("/bin/other", root["mcpServers"]!!.jsonObject["other"]!!.jsonObject["command"]?.jsonPrimitive?.content)
        assertTrue("/a" in root["projects"]!!.jsonObject)
        assertEquals(McpRegistration.REGISTERED, connector.status(binary))
    }

    @Test
    fun `claude reports stale path and re-register fixes it`() {
        val connector = ClaudeCodeConnector(home)
        connector.register(movedBinary)

        assertEquals(McpRegistration.STALE_PATH, connector.status(binary))

        connector.register(binary)
        assertEquals(McpRegistration.REGISTERED, connector.status(binary))
    }

    @Test
    fun `claude upgrades the owned Kitbash registration without leaving a duplicate`() {
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"kitbash":{"type":"stdio","command":"/Applications/Kitbash.app/Contents/MacOS/Kitbash","args":["--mcp"]}}}""",
        )
        val connector = ClaudeCodeConnector(home)

        assertEquals(McpRegistration.STALE_PATH, connector.status(binary))
        connector.register(binary)

        val servers = Json.parseToJsonElement(home.resolve(".claude.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject
        assertFalse("kitbash" in servers)
        assertTrue("ruleblend" in servers)
    }

    @Test
    fun `claude preserves an unrelated server using the old name`() {
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"kitbash":{"type":"stdio","command":"npx","args":["kitbash"]}}}""",
        )
        val connector = ClaudeCodeConnector(home)

        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))
        connector.register(binary)

        val servers = Json.parseToJsonElement(home.resolve(".claude.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject
        assertTrue("kitbash" in servers)
        assertTrue("ruleblend" in servers)
    }

    @Test
    fun `claude unregisters ruleblend and preserves other servers`() {
        home.resolve(".claude.json").writeText(
            """{"numStartups":42,"mcpServers":{"other":{"type":"stdio","command":"/bin/other"}}}""",
        )
        val connector = ClaudeCodeConnector(home)
        connector.register(binary)

        connector.unregister()

        val root = Json.parseToJsonElement(home.resolve(".claude.json").readText()).jsonObject
        assertFalse("ruleblend" in root["mcpServers"]!!.jsonObject)
        assertTrue("other" in root["mcpServers"]!!.jsonObject)
        assertEquals("42", root["numStartups"]?.jsonPrimitive?.content)
        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))
    }

    // --- Codex ---

    @Test
    fun `codex appends section to a fresh config`() {
        val connector = CodexConnector(home)
        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))

        connector.register(binary)

        val text = home.resolve(".codex/config.toml").readText()
        assertTrue("[mcp_servers.ruleblend]" in text)
        assertEquals(binary.toString(), (CodexMcpTable.read(text, MCP_SERVER_NAME) as McpServerConfig.Stdio).command)
        assertTrue("args = [\"--mcp\"]" in text)
        assertEquals(McpRegistration.REGISTERED, connector.status(binary))
    }

    @Test
    fun `codex preserves existing content and comments`() {
        val existing = """
            |# my codex config
            |model = "gpt-6"
            |
            |[mcp_servers.other]
            |command = "/bin/other"
        """.trimMargin()
        home.resolve(".codex/config.toml").writeText(existing + "\n")
        val connector = CodexConnector(home)

        connector.register(binary)

        val text = home.resolve(".codex/config.toml").readText()
        assertTrue("# my codex config" in text)
        assertTrue("model = \"gpt-6\"" in text)
        assertTrue("[mcp_servers.other]" in text)
        assertTrue("[mcp_servers.ruleblend]" in text)
    }

    @Test
    fun `codex replaces its own section in place`() {
        val connector = CodexConnector(home)
        connector.register(movedBinary)
        home.resolve(".codex/config.toml").writeText(
            home.resolve(".codex/config.toml").readText() + "\n[extra]\nkey = 1\n",
        )
        assertEquals(McpRegistration.STALE_PATH, connector.status(binary))

        connector.register(binary)

        val text = home.resolve(".codex/config.toml").readText()
        assertEquals(binary.toString(), (CodexMcpTable.read(text, MCP_SERVER_NAME) as McpServerConfig.Stdio).command)
        assertTrue(movedBinary.toString() !in text)
        assertTrue("[extra]" in text)
        assertEquals(1, Regex("\\[mcp_servers\\.ruleblend]").findAll(text).count())
        assertEquals(McpRegistration.REGISTERED, connector.status(binary))
    }

    @Test
    fun `codex upgrades the owned Kitbash registration without leaving a duplicate`() {
        home.resolve(".codex/config.toml").writeText(
            """
                |[mcp_servers.kitbash]
                |command = "/Users/me/.kitbash/bin/kitbash-mcp"
                |args = ["--mcp"]
            """.trimMargin() + "\n",
        )
        val connector = CodexConnector(home)

        assertEquals(McpRegistration.STALE_PATH, connector.status(binary))
        connector.register(binary)

        val text = home.resolve(".codex/config.toml").readText()
        assertFalse("[mcp_servers.kitbash]" in text)
        assertTrue("[mcp_servers.ruleblend]" in text)
    }

    @Test
    fun `codex preserves an unrelated server using the old name`() {
        home.resolve(".codex/config.toml").writeText(
            """
                |[mcp_servers.kitbash]
                |command = "npx"
                |args = ["kitbash"]
            """.trimMargin() + "\n",
        )
        val connector = CodexConnector(home)

        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))
        connector.register(binary)

        val text = home.resolve(".codex/config.toml").readText()
        assertTrue("[mcp_servers.kitbash]" in text)
        assertTrue("[mcp_servers.ruleblend]" in text)
    }

    @Test
    fun `codex unregisters ruleblend and preserves other sections`() {
        val configFile = home.resolve(".codex/config.toml")
        configFile.writeText(
            """
                |# my codex config
                |model = "gpt-6"
                |
                |[mcp_servers.other]
                |command = "/bin/other"
            """.trimMargin() + "\n",
        )
        val connector = CodexConnector(home)
        connector.register(binary)

        connector.unregister()

        val text = configFile.readText()
        assertFalse("[mcp_servers.ruleblend]" in text)
        assertTrue("[mcp_servers.other]" in text)
        assertTrue("model = \"gpt-6\"" in text)
        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))
    }

    // --- Kimi Code ---

    @Test
    fun `kimi registers in its native MCP config and preserves other servers`() {
        home.resolve(".kimi-code").createDirectories()
        home.resolve(".kimi-code/mcp.json").writeText(
            """{"mcpServers":{"other":{"command":"/bin/other"}},"theme":"dark"}""",
        )
        val connector = KimiCodeConnector(home)

        connector.register(binary)

        val servers = Json.parseToJsonElement(home.resolve(".kimi-code/mcp.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject
        assertEquals("/bin/other", servers["other"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(binary.toString(), servers["ruleblend"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(McpRegistration.REGISTERED, connector.status(binary))

        connector.unregister()
        assertFalse("ruleblend" in Json.parseToJsonElement(home.resolve(".kimi-code/mcp.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject)
        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))
    }

    @Test
    fun `kimi upgrades only its owned Kitbash registration`() {
        home.resolve(".kimi-code").createDirectories()
        home.resolve(".kimi-code/mcp.json").writeText(
            """{"mcpServers":{"kitbash":{"command":"/Applications/Kitbash.app/Contents/MacOS/Kitbash","args":["--mcp"]}}}""",
        )
        val connector = KimiCodeConnector(home)

        assertEquals(McpRegistration.STALE_PATH, connector.status(binary))
        connector.register(binary)

        val servers = Json.parseToJsonElement(home.resolve(".kimi-code/mcp.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject
        assertFalse("kitbash" in servers)
        assertTrue("ruleblend" in servers)
    }

    // --- ZCode ---

    @Test
    fun `zcode registers in its compatible MCP config and preserves other servers`() {
        home.resolve(".zcode").createDirectories()
        home.resolve(".agents").createDirectories()
        home.resolve(".agents/mcp.json").writeText(
            """{"mcpServers":{"other":{"type":"stdio","command":"/bin/other"}},"theme":"dark"}""",
        )
        val connector = ZCodeConnector(home)

        connector.register(binary)

        val servers = Json.parseToJsonElement(home.resolve(".agents/mcp.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject
        assertEquals("/bin/other", servers["other"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(binary.toString(), servers["ruleblend"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(McpRegistration.REGISTERED, connector.status(binary))

        connector.unregister()
        assertFalse("ruleblend" in Json.parseToJsonElement(home.resolve(".agents/mcp.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject)
        assertEquals(McpRegistration.NOT_REGISTERED, connector.status(binary))
    }

    @Test
    fun `pi registers in its native global file and leaves ZCode's entry alone`() {
        home.resolve(".pi/agent").createDirectories()
        home.resolve(".zcode").createDirectories()
        val zcode = ZCodeConnector(home)
        val pi = PiConnector(home)
        zcode.register(binary)

        pi.register(binary)
        assertEquals(McpRegistration.REGISTERED, pi.status(binary))
        pi.unregister()

        assertEquals(McpRegistration.NOT_REGISTERED, pi.status(binary))
        assertEquals(McpRegistration.REGISTERED, zcode.status(binary))
    }

    @Test
    fun `zcode preserves an unrelated old Kitbash server`() {
        home.resolve(".zcode").createDirectories()
        home.resolve(".agents").createDirectories()
        home.resolve(".agents/mcp.json").writeText(
            """{"mcpServers":{"kitbash":{"type":"stdio","command":"npx","args":["kitbash"]}}}""",
        )
        val connector = ZCodeConnector(home)

        connector.register(binary)

        val servers = Json.parseToJsonElement(home.resolve(".agents/mcp.json").readText())
            .jsonObject["mcpServers"]!!.jsonObject
        assertTrue("kitbash" in servers)
        assertTrue("ruleblend" in servers)
    }

    // --- Entry version ---

    @Test
    fun `Windows registration updates and disconnects preserve foreign servers in every format`() {
        home.resolve(".zcode").createDirectories()
        home.resolve(".pi/agent").createDirectories()
        val windowsBinary = home.resolve("Установка с пробелами/Ruleblend.exe")
        val foreign = McpServerConfig.Stdio("C:\\Чужая программа\\server.exe", listOf("--serve"))
        val entries = listOf(
            home.resolve(".claude.json") to ClaudeCodeConnector(home),
            home.resolve(".codex/config.toml") to CodexConnector(home),
            home.resolve(".kimi-code/mcp.json") to KimiCodeConnector(home),
            home.resolve(".agents/mcp.json") to ZCodeConnector(home),
            home.resolve(".pi/agent/mcp.json") to PiConnector(home),
        )
        for ((file, connector) in entries) {
            fun upsert(text: String, name: String, config: McpServerConfig): String = when (connector) {
                is CodexConnector -> CodexMcpTable.upsert(text, name, config)
                is KimiCodeConnector -> KimiMcpJson.upsert(text, name, config)
                else -> McpServersJson.upsert(text, name, McpServersJson.renderEntry(config))
            }
            fun read(name: String): McpServerConfig? = when (connector) {
                is CodexConnector -> CodexMcpTable.read(file.readText(), name)
                else -> McpServersJson.read(file.readText(), name)?.let(McpServersJson::parseEntry)
            }
            file.parent.createDirectories()
            file.writeText(upsert("", "foreign", foreign))
            connector.register(windowsBinary)
            assertEquals(ruleblendEntry(windowsBinary), read(MCP_SERVER_NAME), connector.agentId)
            assertEquals(foreign, read("foreign"), connector.agentId)

            file.writeText(upsert(file.readText(), MCP_SERVER_NAME,
                ruleblendEntry(windowsBinary).copy(env = mapOf(MCP_ENTRY_ENV_KEY to "1"))))
            assertEquals(McpRegistration.OUTDATED, connector.status(windowsBinary), connector.agentId)
            BundledIntegration(connectors = listOf(connector), binary = windowsBinary).updateAll()
            assertEquals(McpRegistration.REGISTERED, connector.status(windowsBinary), connector.agentId)

            connector.register(windowsBinary.resolveSibling("Новая сборка.exe"))
            assertEquals(McpRegistration.STALE_PATH, connector.status(windowsBinary), connector.agentId)
            connector.register(windowsBinary)
            connector.unregister()
            assertEquals(McpRegistration.NOT_REGISTERED, connector.status(windowsBinary), connector.agentId)
            assertEquals(null, read(MCP_SERVER_NAME), connector.agentId)
            assertEquals(foreign, read("foreign"), connector.agentId)
        }
    }

    @Test
    fun `every connector stamps the entry version and reads it back`() {
        home.resolve(".zcode").createDirectories()
        home.resolve(".kimi-code").createDirectories()
        val connectors = listOf(
            ClaudeCodeConnector(home),
            CodexConnector(home),
            ZCodeConnector(home),
            KimiCodeConnector(home),
            PiConnector(home),
        )

        connectors.forEach { connector ->
            connector.register(binary)

            assertEquals(McpRegistration.REGISTERED, connector.status(binary), connector.agentId)
            assertEquals(MCP_ENTRY_VERSION, connector.installedEntryVersion(), connector.agentId)
        }
    }

    @Test
    fun `an entry written before versions existed is outdated, not foreign`() {
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"ruleblend":{"type":"stdio","command":${JsonPrimitive(binary.toString())},"args":["--mcp"]}}}""",
        )
        val connector = ClaudeCodeConnector(home)

        assertEquals(McpRegistration.OUTDATED, connector.status(binary))
        assertEquals(0, connector.installedEntryVersion())

        connector.register(binary)

        assertEquals(McpRegistration.REGISTERED, connector.status(binary))
    }

    @Test
    fun `a moved binary stays a path problem whatever the entry version says`() {
        val connector = ClaudeCodeConnector(home)
        connector.register(binary)

        assertEquals(McpRegistration.STALE_PATH, connector.status(movedBinary))
    }

    @Test
    fun `an unregistered agent reports no entry version`() {
        assertEquals(null, ClaudeCodeConnector(home).installedEntryVersion())
    }

    @Test
    fun `foreign same-name MCP entries survive connect and disconnect in every format`() {
        val entries = listOf(
            Triple(home.resolve(".claude.json"), ClaudeCodeConnector(home),
                """{"mcpServers":{"ruleblend":{"type":"stdio","command":"/bin/foreign","args":["--serve"]}}}"""),
            Triple(home.resolve(".codex/config.toml"), CodexConnector(home),
                "[mcp_servers.ruleblend]\ncommand = \"/bin/foreign\"\nargs = [\"--serve\"]\n"),
            Triple(home.resolve(".kimi-code/mcp.json"), KimiCodeConnector(home),
                """{"mcpServers":{"ruleblend":{"type":"stdio","command":"/bin/foreign","args":["--serve"]}}}"""),
            Triple(home.resolve(".agents/mcp.json"), ZCodeConnector(home),
                """{"mcpServers":{"ruleblend":{"type":"stdio","command":"/bin/foreign","args":["--serve"]}}}"""),
            Triple(home.resolve(".pi/agent/mcp.json"), PiConnector(home),
                """{"mcpServers":{"ruleblend":{"type":"stdio","command":"/bin/foreign","args":["--serve"]}}}"""),
        )
        for ((path, connector, original) in entries) {
            Files.createDirectories(path.parent)
            path.writeText(original)
            assertEquals(McpRegistration.FOREIGN, connector.status(binary), connector.agentId)
            assertEquals(null, connector.installedEntryVersion(), connector.agentId)
            assertFailsWith<IllegalArgumentException> { connector.register(binary) }
            assertFailsWith<IllegalArgumentException> { connector.unregister() }
            assertEquals(original, path.readText(), connector.agentId)
        }
    }

    @Test
    fun `an unmarked same-name executable outside Ruleblend paths is foreign`() {
        val file = home.resolve(".claude.json")
        val original = """{"mcpServers":{"ruleblend":{"type":"stdio","command":"/bin/Ruleblend","args":["--mcp"]}}}"""
        file.writeText(original)
        val connector = ClaudeCodeConnector(home)

        assertEquals(McpRegistration.FOREIGN, connector.status(binary))
        assertFailsWith<IllegalArgumentException> { connector.register(binary) }
        assertEquals(original, file.readText())
    }
}
