package dev.ruleblend.app.library

import dev.ruleblend.app.place.placeId
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.SkipReason
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bulk installer against real files: what it writes, and what it refuses to write. */
class LibraryPlaceInstallServiceTest {

    private lateinit var root: Path
    private lateinit var atlas: Path
    private lateinit var beacon: Path
    private lateinit var configStore: ConfigStore
    private lateinit var installer: LibraryPlaceInstallService

    private val shared = Block("shared-style", "Shared Style", content = "Use spaces.")
    private val pinned = Block("atlas-only", "Atlas Only", content = "Atlas rules.")
    private val mcp = Block("pdf", "PDF", type = BlockType.MCP, content = "{}")

    private fun agent() = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("global/CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-bulk-install")
        atlas = root.resolve("atlas").also { it.createDirectories() }
        beacon = root.resolve("beacon").also { it.createDirectories() }
        root.resolve("global").createDirectories()
        val repository = LibraryRepository(root.resolve("library"))
        repository.init()
        listOf(shared, pinned, mcp).forEach(repository::writeBlock)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.update { config ->
            config.copy(projects = listOf(atlas.projectKey(), beacon.projectKey()))
                .withRuleScope(pinned.id, atlas)
        }
        installer = LibraryPlaceInstallService(
            configStore = configStore,
            agents = listOf(agent()),
            service = IntegrationService(blockResolver = repository::loadBlock),
            mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
        )
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `places list the agent global file and every configured project`() {
        assertEquals(
            listOf("Claude Code", "atlas", "beacon"),
            installer.places().map { it.name },
        )
    }

    @Test
    fun `a global rule lands in every chosen project`() {
        val report = installer.install(
            listOf(InstallItem.Rule(shared, group = null)),
            setOf(projectPlace(atlas), projectPlace(beacon)),
        )

        assertEquals(2, report.written)
        assertEquals(0, report.skippedTotal)
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Use spaces."))
        assertTrue(beacon.resolve("AGENTS.md").readText().contains("Use spaces."))
    }

    @Test
    fun `a project-scoped rule is refused outside its own project`() {
        val report = installer.install(
            listOf(InstallItem.Rule(pinned, group = null)),
            setOf(projectPlace(atlas), projectPlace(beacon)),
        )

        assertEquals(1, report.written)
        assertEquals(mapOf(SkipReason.OUT_OF_SCOPE to 1), report.skipped)
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Atlas rules."))
        assertTrue(Files.notExists(beacon.resolve("AGENTS.md")))
    }

    @Test
    fun `an MCP server is refused where no agent installs one`() {
        val report = installer.install(listOf(InstallItem.Mcp(mcp)), setOf(projectPlace(atlas)))

        assertEquals(0, report.written)
        assertEquals(mapOf(SkipReason.UNSUPPORTED to 1), report.skipped)
    }

    @Test
    fun `a rule edited by hand in one place is refused there and still installed elsewhere`() {
        installer.install(listOf(InstallItem.Rule(shared, group = null)), setOf(projectPlace(atlas)))
        val file = atlas.resolve("AGENTS.md")
        file.toFile().writeText(file.readText().replace("Use spaces.", "Use tabs."))

        val report = installer.install(
            listOf(InstallItem.Rule(shared, group = null)),
            setOf(projectPlace(atlas), projectPlace(beacon)),
        )

        assertEquals(1, report.written)
        assertEquals(mapOf(SkipReason.MODIFIED to 1), report.skipped)
        assertTrue(file.readText().contains("Use tabs."))
    }

    @Test
    fun `a removal takes the rule out of the named place and leaves the other alone`() {
        val places = setOf(projectPlace(atlas), projectPlace(beacon))
        installer.install(listOf(InstallItem.Rule(shared, group = null)), places)

        val report = installer.remove(listOf(InstallItem.Rule(shared, group = null)), setOf(projectPlace(atlas)))

        assertEquals(1, report.written)
        assertEquals(0, report.skippedTotal)
        assertTrue(!atlas.resolve("AGENTS.md").readText().contains("Use spaces."))
        assertTrue(beacon.resolve("AGENTS.md").readText().contains("Use spaces."))
    }

    @Test
    fun `a hand-edited rule is not removed either, the decision belongs to that place`() {
        installer.install(listOf(InstallItem.Rule(shared, group = null)), setOf(projectPlace(atlas)))
        val file = atlas.resolve("AGENTS.md")
        file.toFile().writeText(file.readText().replace("Use spaces.", "Use tabs."))

        val report = installer.remove(listOf(InstallItem.Rule(shared, group = null)), setOf(projectPlace(atlas)))

        assertEquals(0, report.written)
        assertEquals(mapOf(SkipReason.MODIFIED to 1), report.skipped)
        assertTrue(file.readText().contains("Use tabs."))
    }

    @Test
    fun `a rule pinned after it was installed can still be taken out of the foreign project`() {
        installer.install(listOf(InstallItem.Rule(shared, group = null)), setOf(projectPlace(beacon)))
        configStore.update { it.withRuleScope(shared.id, atlas) }

        val report = installer.remove(listOf(InstallItem.Rule(shared, group = null)), setOf(projectPlace(beacon)))

        // The scope rule guards installs, which widen a claim; a removal only ever narrows one.
        assertEquals(1, report.written)
        assertEquals(0, report.skippedTotal)
        assertTrue(!beacon.resolve("AGENTS.md").readText().contains("Use spaces."))
    }

    private fun projectPlace(dir: Path): String = placeId(ProjectTarget(dir, listOf(agent())))
}
