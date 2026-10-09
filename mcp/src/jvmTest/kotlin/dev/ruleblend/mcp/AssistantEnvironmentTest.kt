package dev.ruleblend.mcp

import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.KimiCodeAdapter
import dev.ruleblend.core.integration.KimiCodeHome
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.ZCodeAdapter
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.mcp.install.ClaudeCodeMcpInstaller
import dev.ruleblend.mcp.install.CodexMcpInstaller
import dev.ruleblend.mcp.install.KimiCodeMcpInstaller
import dev.ruleblend.mcp.install.McpInstallRecord
import dev.ruleblend.mcp.install.McpServersJson
import dev.ruleblend.mcp.install.McpStateStore
import dev.ruleblend.mcp.install.PiMcpInstaller
import dev.ruleblend.mcp.install.ZCodeMcpInstaller
import dev.ruleblend.mcp.skill.BundledSkill
import dev.ruleblend.mcp.skill.ClaudeCodeSkillInstaller
import dev.ruleblend.mcp.skill.CodexSkillInstaller
import dev.ruleblend.mcp.skill.KimiCodeSkillInstaller
import dev.ruleblend.mcp.skill.PiSkillInstaller
import dev.ruleblend.mcp.skill.SkillStatus
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantEnvironmentTest {
    private val root = Files.createTempDirectory("ruleblend-mcp-env-")
    private val home = Files.createDirectories(root.resolve("Пользователь с пробелом"))
    private val paths = AssistantPaths(home, mapOf(
        "CODEX_HOME" to home.resolve("custom-codex").toString(),
        "CLAUDE_CONFIG_DIR" to home.resolve("custom-claude").toString(),
        "PI_CODING_AGENT_DIR" to home.resolve("custom-pi").toString(),
        "KIMI_CODE_HOME" to home.resolve("custom-kimi").toString(),
    ))
    private val binary = home.resolve("Program Files/Ruleblend.exe")

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun connectorsAndInstallersUseSameOverrideAndPreserveForeignEntries() {
        Files.createDirectories(paths.claude)
        Files.createDirectories(paths.codex)
        Files.createDirectories(paths.pi)
        val claude = ClaudeCodeConnector(paths = paths)
        val codex = CodexConnector(paths = paths)
        val pi = PiConnector(paths = paths)
        val globalClaude = AgentGlobalTarget(ClaudeCodeAdapter(paths = paths))
        val globalCodex = AgentGlobalTarget(CodexAdapter(paths = paths))
        val globalPi = AgentGlobalTarget(PiAdapter(paths = paths))
        val claudeInstaller = ClaudeCodeMcpInstaller(paths = paths)
        val codexInstaller = CodexMcpInstaller(paths = paths)
        val piInstaller = PiMcpInstaller(paths = paths)
        assertEquals(paths.claudeConfig, claudeInstaller.configFile(globalClaude))
        assertEquals(paths.codex.resolve("config.toml"), codexInstaller.configFile(globalCodex))
        val piFile = paths.pi.resolve("mcp.json")
        assertEquals(piFile, piInstaller.configFile(globalPi))
        val piOriginal = """{"mcpServers":{"foreign":{"command":"foreign.exe"}}}"""
        piFile.writeText(piOriginal)
        paths.claudeConfig.writeText("""{"userSetting":true,"mcpServers":{"foreign":{"command":"foreign.exe"}}}""")
        val codexFile = paths.codex.resolve("config.toml")
        val original = "# user's comment\nmodel = \"test\"\n\n[mcp_servers.foreign]\ncommand = \"foreign.exe\"\n"
        codexFile.writeText(original)
        for (connector in listOf(claude, codex, pi)) {
            assertTrue(connector.isAvailable())
            connector.register(binary)
            assertEquals(McpRegistration.REGISTERED, connector.status(binary))
        }
        assertEquals(McpServerConfig.Stdio("foreign.exe"), claudeInstaller.installed(globalClaude, "foreign"))
        assertEquals(McpServerConfig.Stdio("foreign.exe"), codexInstaller.installed(globalCodex, "foreign"))
        assertEquals(McpServerConfig.Stdio("foreign.exe"), piInstaller.installed(globalPi, "foreign"))
        assertEquals(ruleblendEntry(binary), piInstaller.installed(globalPi, MCP_SERVER_NAME))
        claude.unregister()
        codex.unregister()
        pi.unregister()
        assertEquals(Json.parseToJsonElement(piOriginal), Json.parseToJsonElement(piFile.readText()))
        assertTrue(paths.claudeConfig.readText().contains("\"userSetting\": true"))
        assertEquals(listOf("foreign"), McpServersJson.names(paths.claudeConfig.readText()))
        assertTrue(codexFile.readText().contains("# user's comment"))
        assertEquals(McpServerConfig.Stdio("foreign.exe"), codexInstaller.installed(globalCodex, "foreign"))
        assertFalse(Files.exists(home.resolve(".codex/config.toml")))
        assertFalse(Files.exists(home.resolve(".claude.json")))
        assertFalse(Files.exists(home.resolve(".pi/agent/mcp.json")))
    }

    @Test fun bundledSkillsFollowOverridesAndProtectForeignFiles() {
        val kimi = KimiCodeHome.current(paths)
        val installers = listOf(
            ClaudeCodeSkillInstaller(paths = paths) to paths.claude.resolve("skills"),
            CodexSkillInstaller(paths = paths) to paths.codex.resolve("skills"),
            PiSkillInstaller(paths = paths) to home.resolve(".agents/skills"),
            KimiCodeSkillInstaller(kimi) to paths.kimi.resolve("skills"),
        )
        for ((installer, directory) in installers) {
            val file = directory.resolve(BundledSkill.NAME).resolve("SKILL.md")
            installer.install()
            assertEquals(SkillStatus.INSTALLED, installer.status())
            assertEquals(BundledSkill.text, file.readText())
            installer.uninstall()
            Files.createDirectories(file.parent)
            file.writeText("foreign")
            assertFailsWith<IllegalStateException> { installer.install() }
            installer.uninstall()
            assertEquals("foreign", file.readText())
        }
    }

    @Test fun kimiPiAndZcodeKeepSharedHomesAndProjectConfigsNative() {
        val kimi = KimiCodeHome.current(paths)
        val project = ProjectTarget(home.resolve("project"), emptyList())
        val kimiInstaller = KimiCodeMcpInstaller(kimi)
        val piInstaller = PiMcpInstaller(paths = paths)
        val zcodeInstaller = ZCodeMcpInstaller(home)
        assertEquals(paths.kimi.resolve("mcp.json"), kimiInstaller.configFile(AgentGlobalTarget(KimiCodeAdapter(kimi))))
        assertEquals(paths.pi.resolve("mcp.json"), piInstaller.configFile(AgentGlobalTarget(PiAdapter(paths = paths))))
        assertEquals(home.resolve(".agents/mcp.json"), zcodeInstaller.configFile(AgentGlobalTarget(ZCodeAdapter(home))))
        for (installer in listOf(ClaudeCodeMcpInstaller(paths = paths), zcodeInstaller)) {
            assertEquals(project.dir.resolve(".mcp.json"), installer.configFile(project))
        }
        assertEquals(project.dir.resolve(".kimi-code/mcp.json"), kimiInstaller.configFile(project))
        assertEquals(project.dir.resolve(".codex/config.toml"), CodexMcpInstaller(paths = paths).configFile(project))
        assertEquals(project.dir.resolve(".pi/mcp.json"), piInstaller.configFile(project))
        Files.createDirectories(paths.pi)
        assertTrue(PiConnector(paths = paths).isAvailable())
    }

    @Test fun legacyMcpOwnershipMigratesAndConflictingRecordsStayUntrusted() {
        val project = Files.createDirectories(home.resolve("project"))
        val alias = if (File.separatorChar == '\\') project.toString().uppercase().replace('\\', '/')
            else project.resolve("../project").toString()
        val record = McpInstallRecord("project:$alias", "codex", "server", 1, McpServerConfig.Stdio("a.exe"), "p=work")
        val store = McpStateStore(root)
        store.file.writeText(Json.encodeToString(listOf(record)))
        val key = "project:${project.projectKey()}"
        assertEquals("p=work", store.find(key, "codex", "server")?.origin)
        store.record(record.copy(targetKey = "agent"))
        assertEquals(key, Json.decodeFromString<List<McpInstallRecord>>(store.file.readText()).first().targetKey)
        store.file.writeText(Json.encodeToString(listOf(record, record.copy(targetKey = key, version = 2))))
        assertEquals(2, store.all().size)
        assertNull(store.find(key, "codex", "server"))
        store.remove("project:$alias", "server")
        assertTrue(store.all().isEmpty())
    }
}
