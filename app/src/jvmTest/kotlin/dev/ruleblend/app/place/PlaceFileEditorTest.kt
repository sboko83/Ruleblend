package dev.ruleblend.app.place

import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ruleblend.app.i18n.EnStrings
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
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.integration.WrappedRun
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The in-app file editor is a working surface, not a question: it opens across the window, and the
 * text of the file gets the width the window has. A dialog that opens as a strip wraps a rule every
 * third word, which is the one thing an editor may not do to the text being edited.
 */
class PlaceFileEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val strings = EnStrings
    private val installed = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")
    private val handLine = "Keep the ledger tidy."

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-file-editor").toRealPath()
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(installed)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("$handLine\n", regionFor(installed)),
        )
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
            runBlocking { model.refreshUsage() }
        }
    }

    @Test fun `the file editor opens across the window, not as a strip beside it`() {
        var windowPx = 0f
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                // The window itself is the yardstick: the floor is a share of what the app has, and
                // a fixed width here would measure the dialog against a number nobody sized it from.
                windowPx = LocalWindowInfo.current.containerSize.width.toFloat()
                PlaceScreen(model())
            }
        }

        // Two ways in now: the file's own header in the preview and the palette's Found row. The
        // header comes first in the tree, and it is the one this test is about — it opens the file
        // the preview is showing.
        compose.onAllNodesWithText(strings.intEditFile).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.intFileEditorTitle("AGENTS.md")).assertExists()

        // The file's own text is on screen twice now — once in the preview behind the dialog, once in
        // the editor. The editor is the wider of the two, so the widest is what is being measured.
        val widest = compose.onAllNodesWithText(handLine, substring = true)
            .fetchSemanticsNodes()
            .maxOf { it.boundsInRoot.width }
        assertTrue(
            widest >= windowPx * 0.7f,
            "the editor is ${(widest / windowPx * 100).toInt()}% of the window, under the 70% floor",
        )
    }

    /**
     * Reading a rule and reading what another place made of it are the same errand, so the editor
     * offers both. The global CLAUDE.md is the other file here, and it is what the picker opens on.
     */
    @Test fun `the file editor can compare the open file with another place`() {
        root.resolve("CLAUDE.md").writeText("$handLine\nA line only the global file has.\n")
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) { PlaceScreen(model()) }
        }

        compose.onAllNodesWithText(strings.intEditFile).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FileEditCompareTag).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.compareFileTitle("AGENTS.md")).assertExists()
        compose.onNodeWithText("A line only the global file has.", substring = true).assertExists()
    }
}
