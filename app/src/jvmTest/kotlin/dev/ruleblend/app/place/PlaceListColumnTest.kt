package dev.ruleblend.app.place

import dev.ruleblend.core.config.projectKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking

class PlaceListColumnTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var configStore: ConfigStore
    private lateinit var model: IntegrationModel

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Path.of(Files.createTempDirectory("ruleblend-place-column").projectKey())
        val atlas = root.resolve("atlas").also { it.createDirectories() }
        val orbit = root.resolve("orbit").also { it.createDirectories() }
        val repository = LibraryRepository(root.resolve("library")).also { it.init() }
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(
            AppConfig(
                projects = listOf(atlas.toString(), orbit.toString()),
                places = PlaceBoard(sets = listOf(ProjectSet("KMP apps", listOf(atlas.toString())))),
            ),
        )
        model = IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent(root)),
            backupService = BackupService(root.resolve("backups")),
            service = IntegrationService(blockResolver = repository::loadBlock),
            mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
        ).also { m -> runBlocking { m.load() } }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `the column lists agents, sets and ungrouped projects in both themes`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { RuleblendTheme(theme) { PlaceListColumn(model, onRemoveProject = {}) } }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText(EnStrings.intAgents.uppercase()).assertIsDisplayed()
            compose.onNodeWithText("KMP APPS").assertIsDisplayed()
            compose.onNodeWithText("PROJECTS").assertIsDisplayed()
            compose.onNodeWithText("atlas").assertIsDisplayed()
            compose.onNodeWithText("orbit").assertIsDisplayed()
        }
    }

    @Test
    fun `the star of a row is the pin, and pinned places lead the list`() {
        val orbit = root.resolve("orbit")
        configStore.save(configStore.load().copy(places = PlaceBoard(pinned = listOf("project:$orbit"))))
        runBlocking { model.load() }
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceListColumn(model, onRemoveProject = {}) } }

        val pinnedHeader = compose.onNodeWithText("PINNED").fetchSemanticsNode().positionInRoot.y
        val projectsHeader = compose.onNodeWithText("PROJECTS").fetchSemanticsNode().positionInRoot.y
        assertTrue(pinnedHeader < projectsHeader)

        // The place is listed twice — under the pin and under its project section — so both rows
        // carry the star and either one takes the pin back off.
        compose.onAllNodesWithText("★").onFirst().performClick()
        compose.waitForIdle()

        assertEquals(emptyList(), configStore.load().places.pinned)
    }

    @Test
    fun `the filter field clears itself, and never hides the place that is open`() {
        model.projectTargets.first { it.dir == root.resolve("atlas") }.let{ arg -> runBlocking { model.select(arg) } }
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceListColumn(model, onRemoveProject = {}) } }

        compose.onNodeWithText(EnStrings.placeFilter).performTextInput("orbit")
        compose.waitForIdle()
        // The query filters the list — the agent global is gone — and the open place stays: it is
        // where the centre column is pointed, so losing it from the list loses the reader.
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.onAllNodesWithText("atlas").onFirst().assertIsDisplayed()

        compose.onNodeWithContentDescription(EnStrings.placeFilterClear).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(EnStrings.placeFilter).assertIsDisplayed()
        compose.onNodeWithText("Claude Code").assertIsDisplayed()
    }

    @Test
    fun `opening a place records it as recent, and the config keeps it`() {
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceListColumn(model, onRemoveProject = {}) } }

        compose.onNodeWithText("orbit").performClick()
        compose.waitForIdle()

        assertEquals(listOf("project:${root.resolve("orbit")}"), configStore.load().places.recent)
        compose.onNodeWithText("RECENT").assertIsDisplayed()
    }
}
