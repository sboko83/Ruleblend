package dev.ruleblend.mcp.install

import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.ZCodeAdapter
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.mcp.McpRegistration
import dev.ruleblend.mcp.ZCodeConnector
import dev.ruleblend.mcp.ruleblendEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZCodeMcpMigrationTest {
    private val home = Files.createTempDirectory("ruleblend-zcode-migration")
    private val state = McpStateStore(home.resolve(".ruleblend"))
    private val shared = home.resolve(".agents/mcp.json")
    private val native = home.resolve(".zcode/cli/config.json")
    private val config = McpServerConfig.Http("https://example.invalid", mapOf("X-Key" to "secret"))
    private val binary = home.resolve(".ruleblend/bin/ruleblend-mcp")
    private val migration get() = ZCodeMcpMigration(home, state = state)

    @AfterTest
    fun cleanUp() { home.toFile().deleteRecursively() }

    private fun write(path: Path, text: String) { path.parent.createDirectories(); path.writeText(text) }
    private fun legacy(path: Path, name: String, config: McpServerConfig) {
        write(path, McpServersJson.upsert(if (path.exists()) path.readText() else "", name, McpServersJson.renderEntry(config)))
    }
    private fun record(key: String = "agent", name: String = "remote") {
        state.record(McpInstallRecord(key, "zcode", "library-id", 2, config, origin = "g=group", entryName = name))
    }

    @Test
    fun `global migration moves only owned entries and preserves native settings and sidecar identity`() {
        legacy(shared, "remote", config)
        legacy(shared, "ruleblend", ruleblendEntry(binary))
        legacy(shared, "foreign", McpServerConfig.Stdio("foreign"))
        record()
        val settings = """{"plugins":{"enabled":["sample"]},"mcp":{"timeout":30}}"""
        write(native, settings)

        assertEquals(2, migration.migrate())
        assertEquals(config, ZCodeMcpJson.read(native.readText(), "remote")?.let(McpServersJson::parseEntry))
        assertEquals(McpRegistration.REGISTERED, ZCodeConnector(home).status(binary))
        assertEquals(listOf("foreign"), McpServersJson.names(shared.readText()))
        val installed = assertNotNull(state.find("agent", "zcode", "library-id"))
        assertEquals(native.toString(), installed.configPath)
        assertEquals("g=group", installed.origin)
        assertEquals("remote", installed.entryName)
        assertEquals(0, migration.migrate())
        val withoutServers = ZCodeMcpJson.remove(ZCodeMcpJson.remove(native.readText(), "remote"), "ruleblend")
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(settings),
            kotlinx.serialization.json.Json.parseToJsonElement(withoutServers).let { root ->
                val obj = root as kotlinx.serialization.json.JsonObject
                val mcp = obj["mcp"] as kotlinx.serialization.json.JsonObject
                kotlinx.serialization.json.JsonObject(obj + ("mcp" to kotlinx.serialization.json.JsonObject(mcp - "servers")))
            })
    }

    @Test
    fun `project migration keeps Claude file and never resurrects a removed native entry`() {
        val project = home.resolve("project").also { it.createDirectories() }
        val old = project.resolve(".mcp.json")
        legacy(old, "remote", config)
        record("project:$project")
        val original = old.readText()
        assertEquals(1, migration.migrate())
        assertEquals(original, old.readText())
        val installer = ZCodeMcpInstaller(home)
        val target = ProjectTarget(project, listOf(ZCodeAdapter(home)))
        assertEquals(config, installer.installed(target, "remote"))
        installer.remove(target, "remote")
        assertEquals(0, migration.migrate())
        assertNull(installer.installed(target, "remote"))
    }

    @Test
    fun `modified and foreign entries are not migrated`() {
        legacy(shared, "remote", config.copy(url = "https://changed.invalid"))
        legacy(shared, "ruleblend", McpServerConfig.Stdio("unrelated"))
        record()
        val original = shared.readText()
        assertEquals(0, migration.migrate())
        assertEquals(original, shared.readText())
        assertFalse(native.exists())
        assertNull(state.all().single().configPath)
    }

    @Test
    fun `destination conflicts and malformed native files leave sources and state intact`() {
        legacy(shared, "remote", config)
        record()
        val original = shared.readText()
        for (settings in listOf(
            ZCodeMcpJson.upsert("", "remote", McpServersJson.renderEntry(McpServerConfig.Stdio("foreign"))),
            """{"mcp":{"servers":[]}}""",
        )) {
            write(native, settings)
            assertEquals(0, migration.migrate())
            assertEquals(settings, native.readText())
            assertEquals(original, shared.readText())
            assertNull(state.all().single().configPath)
        }
    }

    @Test
    fun `install and disconnect use only native config and preserve other MCP options`() {
        val target = AgentGlobalTarget(ZCodeAdapter(home))
        val installer = ZCodeMcpInstaller(home)
        write(native, """{"plugins":{},"mcp":{"timeout":5,"servers":{"foreign":{"command":"foreign"}}}}""")
        val original = kotlinx.serialization.json.Json.parseToJsonElement(native.readText())
        installer.install(target, "remote", config)
        ZCodeConnector(home).register(binary)
        assertEquals(config, installer.installed(target, "remote"))
        installer.remove(target, "remote")
        ZCodeConnector(home).unregister()
        assertEquals(original, kotlinx.serialization.json.Json.parseToJsonElement(native.readText()))
        assertFalse(home.resolve(".agents").exists())
    }
}
