package dev.ruleblend.app.home

import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.hashContent
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.storage.LibraryRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The Home scan against real files: what the classes of problem mean on disk, and what one scan of
 * the design's scale (30 places) costs. Home only reads — every assertion here is about what a scan
 * reports, never about a file changing.
 */
class HomeScanTest {

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore

    private val kotlinStyle = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")
    private val gitFlow = Block(id = "git-flow", name = "Git flow", content = "Rebase, do not merge.")

    @Test fun `a hand edit and a pending update land in their own cards`() = runBlocking {
        val edited = project("ledger-kmp")
        val behind = project("relay-cli")
        install(edited, kotlinStyle)
        install(behind, gitFlow)
        edited.resolve("AGENTS.md").let { it.writeText(it.readText().replace(kotlinStyle.content, "Edited by hand.")) }
        repository.saveBlock(gitFlow.copy(content = "Rebase, always."))

        val snapshot = model().apply { rescan() }.snapshot

        assertEquals(listOf("ledger-kmp"), snapshot.card(AttentionKind.CONFLICTS)!!.rows.map { it.name })
        assertEquals(1, snapshot.card(AttentionKind.CONFLICTS)!!.total)
        assertEquals(listOf("relay-cli"), snapshot.card(AttentionKind.UPDATES)!!.rows.map { it.name })
        assertEquals(1, snapshot.card(AttentionKind.UPDATES)!!.total)
    }

    @Test fun `hand-written text is counted as unadopted, a pure pointer is not`() = runBlocking {
        val agent = agent("claude-code", "Claude Code", root.resolve("home-claude"))
        val project = project("mesh-sync")
        project.resolve("AGENTS.md").writeText("# House rules\n\nAlways rebase.\n")
        IntegrationService(blockResolver = repository::loadBlock)
            .syncRedirects(ProjectTarget(project, listOf(agent)))

        val snapshot = model().apply { rescan() }.snapshot

        val card = snapshot.card(AttentionKind.UNADOPTED)!!
        assertEquals(listOf("AGENTS.md"), card.rows.single().files)
        assertEquals(3, card.total)
    }

    @Test fun `a legacy marker file is reported as markup, not as a hand edit`() = runBlocking {
        val project = project("dune-sandbox")
        project.resolve("AGENTS.md").writeText(
            "<!-- ruleblend:begin id=kotlin-style v=1 hash=${hashContent(kotlinStyle.content)} -->\n" +
                "${kotlinStyle.content}\n<!-- ruleblend:end id=kotlin-style -->\n",
        )

        val snapshot = model().apply { rescan() }.snapshot

        assertEquals(1, snapshot.card(AttentionKind.LEGACY)!!.total)
        assertEquals(listOf("dune-sandbox"), snapshot.card(AttentionKind.LEGACY)!!.rows.map { it.name })
        assertNull(snapshot.card(AttentionKind.CONFLICTS))
    }

    @Test fun `an agent with an untouched global file is offered, a managed one is not`() = runBlocking {
        val agent = agent("claude-code", "Claude Code", root.resolve("home-claude"))
        agent.globalFile().parent.createDirectories()
        agent.globalFile().writeText("# My global rules\n")

        val detected = model(listOf(agent)).apply { rescan() }.snapshot.card(AttentionKind.NEW_AGENT)!!
        assertEquals("Claude Code", detected.agent?.name)

        IntegrationService(blockResolver = repository::loadBlock).install(agent.globalFile(), kotlinStyle)

        assertNull(model(listOf(agent)).apply { rescan() }.snapshot.card(AttentionKind.NEW_AGENT))
    }

    @Test fun `an untouched project is not a problem and not a missing place`() = runBlocking {
        project("quartz-kmp")

        val snapshot = model().apply { rescan() }.snapshot

        assertTrue(snapshot.allClear)
        // The agent's global file is a place of its own, listed even when it holds nothing.
        assertEquals(listOf("Claude Code", "quartz-kmp"), snapshot.fleet.places.map { it.name })
    }

    @Test fun `Home lists only projects that have a real profile switch to make`() = runBlocking {
        val listed = project("ledger-kmp")
        val single = project("relay-cli")
        repository.saveProfile(Profile("review", "Review"))
        repository.saveProfile(Profile("testing", "Testing"))
        configStore.update {
            it.copy(
                projectProfiles = mapOf(
                    listed.toString() to listOf(ProfileBinding("review", active = true), ProfileBinding("testing", active = false)),
                    single.toString() to listOf(ProfileBinding("review", active = true)),
                ),
            )
        }

        val snapshot = model().apply { rescan() }.snapshot

        assertEquals(listOf("ledger-kmp"), snapshot.profileProjects.map { it.name })
        assertEquals(listOf("Review", "Testing"), snapshot.profileProjects.single().profiles.map { it.name })
    }

    /** The design's scale: 5 agent globals + 25 projects = 30 places, against a 304-object library. */
    @Test fun `one scan covers the design scale of 30 places`() = runBlocking {
        val rules = (1..304).map { Block(id = "rule-$it", name = "Rule $it", content = "Body $it.") }
        rules.forEach(repository::saveBlock)
        val installed = rules.take(12)
        val agents = (1..5).map { agent("agent-$it", "Agent $it", root.resolve("home-$it")) }
        agents.forEach { agent ->
            agent.globalFile().parent.createDirectories()
            installed.forEach { IntegrationService(blockResolver = repository::loadBlock).install(agent.globalFile(), it) }
        }
        repeat(25) { index ->
            val project = project("project-$index")
            // Hand-written text first: installing into it produces a partial run, which is what a
            // real project looks like — an owned file has nothing left to adopt by definition.
            project.resolve("AGENTS.md").writeText("# Notes\n\nHand written.\n")
            installed.forEach { install(project, it) }
        }

        val model = model(agents)
        model.rescan()
        val millis = model.lastScanMillis!!

        assertEquals(30, model.snapshot.fleet.total)
        assertEquals(30 * 12, model.snapshot.fleet.places.sumOf { it.synced })
        assertEquals(25, model.snapshot.card(AttentionKind.UNADOPTED)!!.places)
        println("Home scan of 30 places × 304 library objects: $millis ms")
        // The design target is well under 3 s on a developer machine; shared CI runners measured just
        // above it, so the bound only guards against a scan that stops scaling.
        assertTrue(millis < 10_000, "one scan of 30 places took $millis ms")
    }

    // ---- harness ----

    private fun agent(id: String, name: String, home: Path) = object : AgentAdapter {
        override val id = id
        override val name = name
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("AGENTS.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
        override fun projectRedirectFile(projectDir: Path): Path = projectDir.resolve("CLAUDE.md")
    }

    private fun project(name: String): Path {
        val dir = root.resolve(name).also { it.createDirectories() }
        configStore.update { it.copy(projects = it.projects + dir.toString()) }
        return dir
    }

    private fun install(project: Path, block: Block) {
        IntegrationService(blockResolver = repository::loadBlock).install(project.resolve("AGENTS.md"), block)
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-home-scan")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(kotlinStyle)
        repository.saveBlock(gitFlow)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig())
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model(agents: List<AgentAdapter> = emptyList()): HomeModel {
        val service = IntegrationService(blockResolver = repository::loadBlock)
        val mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root))
        val skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null })
        val all = agents.ifEmpty { listOf(agent("claude-code", "Claude Code", root.resolve("home-claude"))) }
        return HomeModel(
            repository = repository,
            configStore = configStore,
            agents = all,
            usageSource = LibraryUsageScanner(configStore, all, service, mcpService, skillService),
        )
    }
}
