package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.ClaudeCodeMcpInstaller
import dev.ruleblend.mcp.install.CodexMcpInstaller
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * "Save as version" for an MCP server in a project that covers two agents. The library keeps one
 * body per block, so two agents edited apart have no single answer — and the wrong answer here
 * silently drops one of the two edits.
 */
class PlaceMcpVersionTest {
    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore

    private val server = Block(
        id = "context7",
        name = "context7",
        type = BlockType.MCP,
        content = McpConfigCodec.serialize(McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7"))),
    )

    private fun agent(agentId: String, agentName: String, home: Path) = object : AgentAdapter {
        override val id = agentId
        override val name = agentName
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("$agentId.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-mcp-version").toRealPath()
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(server)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private val agents get() = listOf(agent("claude-code", "Claude Code", root), agent("codex", "Codex", root))

    private fun model(): IntegrationModel {
        val service = IntegrationService(blockResolver = repository::loadBlock)
        val mcpService = McpInstallService(
            installers = listOf(ClaudeCodeMcpInstaller(root), CodexMcpInstaller(root)),
            state = McpStateStore(root),
        )
        val skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null })
        return IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = agents,
            backupService = BackupService(root.resolve("backups")),
            service = service,
            mcpService = mcpService,
            skillService = skillService,
            usageSource = LibraryUsageScanner(configStore, agents, service, mcpService, skillService),
        ).also { model ->
            runBlocking { model.load() }
            model.projectTargets.first { it.dir == project }.let{ arg -> runBlocking { model.select(arg) } }
        }
    }

    private val claudeFile get() = project.resolve(".mcp.json")
    private val codexFile get() = project.resolve(".codex/config.toml")

    @Test fun `two agents edited apart keep both edits and report the clash`() {
        runBlocking { model().install(server) }
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "/opt/wrapper"))

        val model = model()
        runBlocking { model.acceptLocalChange(server) }

        assertNotNull(model.failure)
        assertTrue("bunx" in claudeFile.readText())
        assertTrue("/opt/wrapper" in codexFile.readText())
        assertEquals(1, repository.loadBlock(server.id)?.version)
    }

    @Test fun `one edited agent and one untouched is a clash too`() {
        runBlocking { model().install(server) }
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))

        val model = model()
        runBlocking { model.acceptLocalChange(server) }

        assertNotNull(model.failure)
        assertTrue("npx" in codexFile.readText())
        assertEquals(1, repository.loadBlock(server.id)?.version)
    }

    @Test fun `copies that came apart are offered per agent, and the named one is saved`() {
        runBlocking { model().install(server) }
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "/opt/wrapper"))

        val model = model()
        val copies = model.mcpDriftedCopies(server.id)
        assertEquals(listOf("Claude Code", "Codex"), copies.map { it.agentName })
        assertTrue("bunx" in copies.first { it.agentId == "claude-code" }.text)
        assertTrue("/opt/wrapper" in copies.first { it.agentId == "codex" }.text)

        runBlocking { model.performPlaceAction(PlaceAction.SAVE_AS_VERSION, LibraryObjectKey(LibraryObjectKind.MCP, server.id), "claude-code") }

        assertNull(model.failure)
        assertEquals(2, repository.loadBlock(server.id)?.version)
        assertTrue("bunx" in repository.loadBlock(server.id)?.content.orEmpty())
        assertTrue("bunx" in claudeFile.readText())
        assertTrue("bunx" in codexFile.readText())
    }

    @Test fun `agreeing copies are not offered as a choice`() {
        runBlocking { model().install(server) }
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "bunx"))

        assertEquals(emptyList(), model().mcpDriftedCopies(server.id))
    }

    @Test fun `one edit shared by both agents becomes the next version`() {
        runBlocking { model().install(server) }
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "bunx"))

        val model = model()
        runBlocking { model.acceptLocalChange(server) }

        assertNull(model.failure)
        assertEquals(2, repository.loadBlock(server.id)?.version)
        assertTrue("bunx" in repository.loadBlock(server.id)?.content.orEmpty())
        assertTrue("bunx" in claudeFile.readText())
        assertTrue("bunx" in codexFile.readText())
    }
}
