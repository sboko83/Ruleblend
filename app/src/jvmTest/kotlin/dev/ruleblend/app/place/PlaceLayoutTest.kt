package dev.ruleblend.app.place

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
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
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * The Place surface has to survive a window narrower than its three columns. The centre keeps the
 * file preview readable; the side columns shrink first and are dropped from the leading side.
 */
class PlaceLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
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
        root = Files.createTempDirectory("ruleblend-place-layout")
        val atlas = root.resolve("atlas").also { it.createDirectories() }
        val repository = LibraryRepository(root.resolve("library")).also { it.init() }
        val configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(atlas.toString())))
        model = IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent(root)),
            backupService = BackupService(root.resolve("backups")),
            service = IntegrationService(blockResolver = repository::loadBlock),
            mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
        ).also { m -> runBlocking { m.load() } }
        runBlocking { model.select(model.configuredPlaces.projects.first()) }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `columns give way to the file preview as the window narrows, in both themes`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var width by mutableStateOf(1440.dp)
        compose.setContent {
            RuleblendTheme(theme) {
                Box(Modifier.width(width).fillMaxHeight()) { PlaceScreen(model) }
            }
        }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            // Calculated width, then the minimum window minus the rail: three columns either way.
            listOf(1440.dp, 908.dp).forEach { wide ->
                compose.runOnIdle { width = wide }
                compose.onNodeWithText("Filter targets…").assertExists()
                compose.onNodeWithText("LIBRARY").assertExists()
            }
            // Narrower than both panes: the place list goes, the palette stays — places are one ⌘K
            // away, while nothing else installs into this file.
            compose.runOnIdle { width = 560.dp }
            compose.onNodeWithText("Filter targets…").assertDoesNotExist()
            compose.onNodeWithText("LIBRARY").assertExists()
        }
    }
}
