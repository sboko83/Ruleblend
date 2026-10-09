package dev.ruleblend.app.place

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.CliLaunch
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentCli
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

/**
 * Setting a project up and working in it are the same session, so the project header offers the
 * agents this machine can actually start — and starts them where the work is, in the project's own
 * folder. An agent global has no folder to work in and therefore no button.
 */
class PlaceLaunchTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val launches = mutableListOf<CliLaunch>()

    /** Claude Code can be resumed; the second agent cannot, which is what the menu is read against. */
    private fun agent(id: String, name: String, cli: AgentCli?) = object : AgentAdapter {
        override val id = id
        override val name = name
        override val cli = cli
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("$id.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-launch").toRealPath()
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model(
        agents: List<AgentAdapter> = listOf(agent("claude-code", "Claude Code", AgentCli("claude", listOf("--continue")))),
        // A machine where only some of the CLIs are installed; the rest must not be offered.
        installed: Set<String> = setOf("claude"),
        selectProject: Boolean = true,
    ): IntegrationModel = IntegrationModel(
        repository = repository,
        configStore = configStore,
        agents = agents,
        backupService = BackupService(root.resolve("backups")),
        service = IntegrationService(blockResolver = repository::loadBlock),
        mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
        skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
        launcherDirectory = root.resolve("launchers"),
        cliProbe = { executable ->
            if (executable in installed) CliProbe("/opt/homebrew/bin/$executable", interactive = false) else null
        },
        cliLauncher = { launches += it },
    ).also { model ->
        runBlocking { model.load() }
        if (selectProject) model.projectTargets.first().let{ arg -> runBlocking { model.select(arg) } } else model.agentTargets.first().let{ arg -> runBlocking { model.select(arg) } }
    }

    private fun show(model: IntegrationModel) {
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlaceFileColumn(model) } }
        compose.waitUntil(5_000) { model.cliAgents.isNotEmpty() || model.selected !is dev.ruleblend.core.integration.ProjectTarget }
        compose.waitForIdle()
    }

    @Test fun `a project header starts its agent in the project's folder`() {
        val model = model()
        show(model)

        compose.onNodeWithText("Claude Code").assertIsDisplayed()
        compose.onNodeWithTag("place-launch-claude-code").performClick()
        compose.waitForIdle()

        val launch = launches.single()
        assertEquals(project, launch.directory)
        assertEquals(listOf("claude"), launch.command)
        assertEquals("claude-code", launch.agentId)
    }

    @Test fun `the extra arguments from settings are part of the launch`() {
        configStore.update { it.copy(agentCliArguments = mapOf("claude-code" to """--model opus --prompt "read AGENTS.md"""")) }
        val model = model()
        show(model)

        compose.onNodeWithTag("place-launch-claude-code").performClick()
        compose.waitForIdle()

        assertEquals(listOf("claude", "--model", "opus", "--prompt", "read AGENTS.md"), launches.single().command)
    }

    @Test fun `an agent whose CLI is not on this machine gets no button`() {
        val model = model(
            agents = listOf(
                agent("claude-code", "Claude Code", AgentCli("claude", listOf("--continue"))),
                agent("codex", "Codex", AgentCli("codex", listOf("resume", "--last"))),
            ),
        )
        show(model)

        compose.onNodeWithTag("place-launch-claude-code").assertExists()
        compose.onNodeWithTag("place-launch-codex").assertDoesNotExist()
    }

    @Test fun `an agent global is a file, not a place to work in`() {
        val model = model(selectProject = false)
        show(model)

        compose.onNodeWithTag("place-launch-claude-code").assertDoesNotExist()
        assertTrue(launches.isEmpty())
    }

    @Test fun `resuming a session uses the agent's own arguments`() {
        val model = model()
        show(model)

        model.launchCli("claude-code", dev.ruleblend.core.integration.AgentLaunchMode.RESUME)

        assertEquals(listOf("claude", "--continue"), launches.single().command)
    }

    @Test fun `removing a project also removes its profile bindings`() = runBlocking {
        val other = root.resolve("other").also { it.createDirectories() }
        configStore.update {
            it.copy(
                projects = listOf(project.toString(), other.toString()),
                projectProfiles = mapOf(
                    project.toString() to listOf(ProfileBinding(id = "review", active = true)),
                    other.toString() to listOf(ProfileBinding(id = "focus", active = false)),
                ),
            )
        }
        val model = model()

        model.removeProject(model.projectTargets.single { it.dir == project })

        assertEquals(listOf(other.projectKey()), configStore.load().projects)
        assertEquals(
            mapOf(other.projectKey() to listOf(ProfileBinding(id = "focus", active = false))),
            configStore.load().projectProfiles,
        )
    }
}
