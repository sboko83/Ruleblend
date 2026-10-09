package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.projectKey
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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule

class PlaceOwnershipTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var configStore: ConfigStore
    private lateinit var repository: LibraryRepository

    private fun agent(id: String, name: String) = object : AgentAdapter {
        override val id = id
        override val name = name
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("$id.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-ownership").toRealPath()
        project = root.resolve("project").also { it.createDirectories() }
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model() = IntegrationModel(
        repository = repository,
        configStore = configStore,
        agents = listOf(agent("claude-code", "Claude Code"), agent("codex", "Codex")),
        backupService = BackupService(root.resolve("backups")),
        service = IntegrationService(blockResolver = repository::loadBlock),
        mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
        skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
    ).also { model ->
        runBlocking {
            model.load()
            model.select(model.projectTargets.single())
        }
    }

    @Test
    fun `backup restore and assistant switches keep the file and project scope honest`() = runBlocking {
        val file = project.resolve("AGENTS.md")
        file.writeText("before\n")
        val model = model()

        model.backupFile(file)
        file.writeText("after\n")
        model.restoreFile(file)

        assertEquals("before\n", file.readText())
        assertTrue(file in model.backupRecords)

        val target = model.projectTargets.single()
        assertTrue(model.projectAssistants(target).all { it.enabled })
        model.setProjectAssistantEnabled(target, "codex", enabled = false)

        assertEquals("before\n", file.readText())
        assertEquals(listOf("codex"), configStore.load().disabledAgents[project.projectKey()])
        assertFalse(model.projectAssistants(model.projectTargets.single()).single { it.id == "codex" }.enabled)
        assertEquals(listOf("claude-code"), model.projectTargets.single().agents.map { it.id })
    }

    @Test
    fun `Place exposes file rollback and every project assistant`() {
        val file = project.resolve("AGENTS.md")
        file.writeText("hand-written\n")
        val model = model()

        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceFileColumn(model) } }
        compose.waitForIdle()

        val displayedFile = model.projectTargets.single().files().single()
        compose.onNodeWithTag("place-backup-$displayedFile").assertIsDisplayed()
        // Folded by default: the summary stands in for the switches until it is opened.
        compose.onNodeWithTag("place-assistant-claude-code").assertDoesNotExist()
        compose.onNodeWithTag("place-assistants-summary").performClick()
        compose.onNodeWithTag("place-assistant-claude-code").assertIsDisplayed()
        compose.onNodeWithTag("place-assistant-codex").assertIsDisplayed()
    }

    @Test
    fun `Project assistants panel stays open across a switch and filters by name`() {
        val model = model()

        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceFileColumn(model) } }
        compose.waitForIdle()

        compose.onNodeWithTag("place-assistants-configure").performClick()
        compose.onNodeWithTag("place-assistant-codex").performClick()
        compose.waitUntil(5_000) {
            model.projectAssistants(model.projectTargets.single()).none { it.id == "codex" && it.enabled }
        }
        // A switch rebuilds the project target; the panel must not fold under the pointer.
        compose.onNodeWithTag("place-assistants-panel").assertIsDisplayed()
        compose.onNodeWithTag("place-assistant-codex").assertIsOff()

        compose.onNodeWithTag("place-assistants-search").performTextInput("odex")
        compose.onNodeWithTag("place-assistant-codex").assertIsDisplayed()
        compose.onNodeWithTag("place-assistant-claude-code").assertDoesNotExist()

        compose.onNodeWithTag("place-assistants-collapse").performClick()
        compose.onNodeWithTag("place-assistants-summary").assertIsDisplayed()
    }
}
