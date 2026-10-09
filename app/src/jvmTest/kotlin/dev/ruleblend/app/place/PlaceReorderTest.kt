package dev.ruleblend.app.place

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * Dragging a block into another place: the geometry that decides where it lands, and the write that
 * makes the file agree with what was dragged.
 */
class PlaceReorderTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val first = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")
    private val second = Block(id = "git-safety", name = "Git safety", content = "Never force-push.")

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-reorder").toRealPath()
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(first)
        repository.saveBlock(second)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model(): IntegrationModel {
        val service = IntegrationService(blockResolver = repository::loadBlock)
        val mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root))
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

    private fun agentsFile(): Path = project.resolve("AGENTS.md")

    private fun installedOrder(model: IntegrationModel): List<String> =
        model.fileSegments[agentsFile()].orEmpty()
            .filterIsInstance<dev.ruleblend.core.integration.FileSegment.Run>()
            .flatMap { run -> run.regions.map { it.id } }

    // --- drag geometry -------------------------------------------------------

    @Test fun `a row moves once the drag covers more than half of its neighbour`() {
        val heights = listOf(40f, 40f, 40f)

        assertEquals(0, dropTargetIndex(heights, from = 0, offset = 19f))
        assertEquals(1, dropTargetIndex(heights, from = 0, offset = 21f))
        assertEquals(2, dropTargetIndex(heights, from = 0, offset = 65f))
        assertEquals(0, dropTargetIndex(heights, from = 2, offset = -65f))
    }

    @Test fun `rows of different height are passed by their own size, not an assumed one`() {
        // A drifted block carries an extra line of explanation, so it is taller than its neighbours.
        val heights = listOf(40f, 90f, 40f)

        assertEquals(0, dropTargetIndex(heights, from = 0, offset = 44f))
        assertEquals(1, dropTargetIndex(heights, from = 0, offset = 46f))
    }

    @Test fun `a drag past either end lands on the end row`() {
        val heights = listOf(40f, 40f, 40f)

        assertEquals(2, dropTargetIndex(heights, from = 0, offset = 400f))
        assertEquals(0, dropTargetIndex(heights, from = 2, offset = -400f))
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moveItem(from = 0, to = 9))
    }

    @Test fun `the dragged row is pulled back by the height of every row it passed`() {
        val heights = listOf(40f, 90f, 40f)

        assertEquals(130f, dragOffsetAfterMove(heights, from = 0, to = 2))
        assertEquals(-90f, dragOffsetAfterMove(heights, from = 2, to = 1))
        assertEquals(0f, dragOffsetAfterMove(heights, from = 1, to = 1))
    }

    // --- the write -----------------------------------------------------------

    @Test fun `dropping a block writes the new order into the file`() {
        val model = model()
        agentsFile().writeText("# Project notes\n")
        runBlocking { model.install(first) }
        runBlocking { model.install(second) }
        assertEquals(listOf(first.id, second.id), installedOrder(model))

        runBlocking { model.reorderBlocks(agentsFile(), listOf(second.id, first.id)) }

        assertEquals(listOf(second.id, first.id), installedOrder(model))
        val text = agentsFile().readText()
        assertTrue(text.indexOf(second.content) < text.indexOf(first.content))
    }

    @Test fun `reordering keeps the hand-written text and the block bodies as they were`() {
        val model = model()
        agentsFile().writeText("# Project notes\nRead the docs first.\n")
        runBlocking { model.install(first) }
        runBlocking { model.install(second) }

        runBlocking { model.reorderBlocks(agentsFile(), listOf(second.id, first.id)) }

        val text = agentsFile().readText()
        assertTrue("# Project notes\nRead the docs first." in text)
        assertTrue(first.content in text && second.content in text)
        assertEquals(emptyList(), model.statuses.filterValues { it != dev.ruleblend.core.integration.InstallStatus.SYNCED }.keys.toList())
    }

    @Test fun `one click sends a block to either end of the run`() {
        val model = model()
        agentsFile().writeText("# Project notes\n")
        runBlocking { model.install(first) }
        runBlocking { model.install(second) }
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceFileColumn(model) } }

        // Two blocks, so exactly one row offers each end: the last one can go up, the first down.
        compose.onNodeWithText(MOVE_TOP_GLYPH).performClick()
        compose.waitUntil(5_000) { installedOrder(model) == listOf(second.id, first.id) }

        assertEquals(listOf(second.id, first.id), installedOrder(model))

        compose.onNodeWithText(MOVE_BOTTOM_GLYPH).performClick()
        compose.waitUntil(5_000) { installedOrder(model) == listOf(first.id, second.id) }

        assertEquals(listOf(first.id, second.id), installedOrder(model))
        val text = agentsFile().readText()
        assertTrue(text.indexOf(first.content) < text.indexOf(second.content))
        assertTrue("# Project notes" in text)
    }

    @Test fun `the two ends read as one control, not as two marks beside the version`() {
        val third = Block(id = "docs-first", name = "Docs first", content = "Read the docs.")
        repository.saveBlock(third)
        val model = model()
        runBlocking { model.install(first) }
        runBlocking { model.install(second) }
        runBlocking { model.install(third) }
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceFileColumn(model) } }

        // Three blocks, so the middle row is the one offering both ends: it is the only row where a
        // top glyph and a bottom glyph share a line.
        val tops = compose.onAllNodesWithText(MOVE_TOP_GLYPH).fetchSemanticsNodes().map { it.boundsInRoot }
        val bottoms = compose.onAllNodesWithText(MOVE_BOTTOM_GLYPH).fetchSemanticsNodes().map { it.boundsInRoot }
        val top = tops.first { candidate -> bottoms.any { it.top == candidate.top } }
        val bottom = bottoms.first { it.top == top.top }
        // Unmerged: the version is a text inside a row that merges into one node, and the merged
        // node is as wide as the row — measuring against it would measure nothing.
        val version = compose.onAllNodesWithText("v1", useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .minByOrNull { abs(it.center.y - top.center.y) }!!

        assertTrue(bottom.left - top.right < top.left - version.right, "the pair must be tighter than its gap to the version")
    }

    @Test fun `repeating the same order writes nothing`() {
        val model = model()
        runBlocking { model.install(first) }
        runBlocking { model.install(second) }
        runBlocking { model.reorderBlocks(agentsFile(), listOf(second.id, first.id)) }
        val once = agentsFile().readText()

        runBlocking { model.reorderBlocks(agentsFile(), listOf(second.id, first.id)) }

        assertEquals(once, agentsFile().readText())
        assertEquals(listOf(second.id, first.id), installedOrder(model))
    }
}
