package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.SkipReason
import dev.ruleblend.mcp.install.ClaudeCodeMcpInstaller
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Installing or removing a group is a decision about the bundle, not about any one member's local
 * edit. These run against real files: the point is what the config on disk holds afterwards.
 */
class PlaceGroupWriteTest {
    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore

    private val rule = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")
    private val server = Block(
        id = "context7",
        name = "context7",
        type = BlockType.MCP,
        content = McpConfigCodec.serialize(McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7"))),
    )
    private val group = Group(id = "starter", name = "Starter", blockIds = listOf(rule.id, server.id))

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-group").toRealPath()
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(rule)
        repository.saveBlock(server)
        repository.saveGroup(group)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model(): IntegrationModel {
        val service = IntegrationService(blockResolver = repository::loadBlock)
        val mcpService = McpInstallService(
            installers = listOf(ClaudeCodeMcpInstaller(root)),
            state = McpStateStore(root),
        )
        val skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null })
        return IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent(root)),
            backupService = BackupService(root.resolve("backups")),
            service = service,
            mcpService = mcpService,
            skillService = skillService,
            usageSource = LibraryUsageScanner(configStore, listOf(agent(root)), service, mcpService, skillService),
        ).also { model ->
            runBlocking { model.load() }
            model.projectTargets.first { it.dir == project }.let{ arg -> runBlocking { model.select(arg) } }
        }
    }

    private val mcpFile get() = project.resolve(".mcp.json")

    @Test fun `installing a group writes every member and reports nothing skipped`() {
        val model = model()

        runBlocking { model.installGroup(group) }

        assertTrue(rule.id in project.resolve("AGENTS.md").readText())
        assertTrue("ctx7" in mcpFile.readText())
        assertEquals(0, model.groupReport?.report?.skippedTotal)
    }

    @Test fun `a group member that fails does not hide the members that landed`() {
        // A directory where the rule file belongs: the rule cannot be written, the MCP entry can.
        project.resolve("AGENTS.md").createDirectories()
        val model = model()

        runBlocking { model.installGroup(group) }

        assertTrue("ctx7" in mcpFile.readText())
        val partial = model.failure?.partial
        assertEquals(1, partial?.written)
        assertEquals(1, partial?.failed)
    }

    @Test fun `installing a group keeps a hand-edited MCP entry and says so`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        mcpFile.writeText(mcpFile.readText().replace("npx", "bunx"))

        val after = model()
        runBlocking { after.installGroup(group) }

        assertTrue("bunx" in mcpFile.readText())
        assertEquals(mapOf(SkipReason.MODIFIED to 1), after.groupReport?.report?.skipped)
        assertFalse(after.groupReport?.removal ?: true)
    }

    @Test fun `removing a group keeps a hand-edited MCP entry and says so`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        mcpFile.writeText(mcpFile.readText().replace("npx", "bunx"))

        val after = model()
        runBlocking { after.removeGroup(group) }

        assertTrue("context7" in mcpFile.readText())
        assertFalse(rule.id in project.resolve("AGENTS.md").readText())
        assertEquals(mapOf(SkipReason.MODIFIED to 1), after.groupReport?.report?.skipped)
        assertTrue(after.groupReport?.removal ?: false)
    }

    @Test fun `removing a group keeps a hand-edited rule and says so`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        val agentsFile = project.resolve("AGENTS.md")
        agentsFile.writeText(agentsFile.readText().replace("Prefer data classes.", "Prefer sealed interfaces."))

        val after = model()
        runBlocking { after.removeGroup(group) }

        assertTrue("Prefer sealed interfaces." in agentsFile.readText())
        assertFalse("ctx7" in mcpFile.readText())
        assertEquals(mapOf(SkipReason.MODIFIED to 1), after.groupReport?.report?.skipped)
        assertEquals(1, after.groupReport?.report?.written)
    }

    @Test fun `a member written for the group before origins were recorded stays until confirmed`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        // Before build 165 the group wrote its MCP member exactly like a standalone install.
        val state = root.resolve("mcp-state.json")
        state.writeText(state.readText().replace(Regex(""",?\s*"origin":\s*"g=starter""""), ""))
        assertFalse("g=starter" in state.readText())

        val after = model()
        runBlocking { after.removeGroup(group) }

        assertFalse(rule.id in project.resolve("AGENTS.md").readText())
        assertTrue("ctx7" in mcpFile.readText())
        assertEquals(listOf(server.id), after.groupReport?.unmarked?.map { it.id })

        runBlocking { after.removeUnmarkedGroupMembers() }

        assertFalse("ctx7" in mcpFile.readText())
        assertEquals(1, after.groupReport?.report?.written)
        assertEquals(emptyList(), after.groupReport?.unmarked)
    }

    @Test fun `a member the group marked is taken out without asking`() {
        val model = model()
        runBlocking { model.installGroup(group) }

        val after = model()
        runBlocking { after.removeGroup(group) }

        assertFalse("ctx7" in mcpFile.readText())
        assertEquals(emptyList(), after.groupReport?.unmarked)
    }

    @Test fun `members updated one by one still leave with their group`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        // The per-row update in Projects writes without a group; it must not re-own the copy.
        runBlocking {
            model.install(rule, force = true)
            model.install(server, force = true)
        }

        val after = model()
        runBlocking { after.removeGroup(group) }

        assertFalse(rule.id in project.resolve("AGENTS.md").readText())
        assertFalse("ctx7" in mcpFile.readText())
        assertEquals(emptyList(), after.groupReport?.unmarked)
    }

    @Test fun `removing a group reports nothing for a member that never landed here`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        mcpFile.toFile().delete()

        val after = model()
        runBlocking { after.removeGroup(group) }

        assertEquals(1, after.groupReport?.report?.written)
        assertEquals(0, after.groupReport?.report?.skippedTotal)
    }

    @Test fun `a foreign entry with a member's name survives both group actions`() {
        mcpFile.writeText("""{"mcpServers":{"context7":{"type":"stdio","command":"/usr/local/bin/theirs"}}}""")

        val model = model()
        runBlocking { model.installGroup(group) }
        assertTrue("/usr/local/bin/theirs" in mcpFile.readText())
        assertEquals(mapOf(SkipReason.MODIFIED to 1), model.groupReport?.report?.skipped)

        val after = model()
        runBlocking { after.removeGroup(group) }
        assertTrue("/usr/local/bin/theirs" in mcpFile.readText())
        assertEquals(mapOf(SkipReason.MODIFIED to 1), after.groupReport?.report?.skipped)
    }

    @Test fun `an updated library member is still ours, so the group install takes it`() {
        val model = model()
        runBlocking { model.installGroup(group) }
        repository.saveBlock(
            server.copy(content = McpConfigCodec.serialize(McpServerConfig.Stdio(command = "bunx"))),
        )

        val after = model()
        runBlocking { after.installGroup(group) }

        assertTrue("bunx" in mcpFile.readText())
        assertEquals(0, after.groupReport?.report?.skippedTotal)
    }
}
