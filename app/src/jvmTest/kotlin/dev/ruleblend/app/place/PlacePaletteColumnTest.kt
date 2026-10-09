package dev.ruleblend.app.place

import dev.ruleblend.core.config.projectKey
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.app.library.projectScopeLabel
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.InstallStatus
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
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The right column of Place on real places: what the library offers here, what a sibling project of
 * the same set already installed, and the hand-written files still waiting to be adopted. Both
 * themes, because the design contract treats them as equals.
 */
class PlacePaletteColumnTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var sibling: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val strings = EnStrings
    private val installed = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")
    private val offered = Block(id = "git-flow", name = "Git flow", content = "Rebase, never merge.")

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Path.of(Files.createTempDirectory("ruleblend-place-palette").projectKey())
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        sibling = root.resolve("transit-ios").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(installed)
        repository.saveBlock(offered)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(
            AppConfig(
                projects = listOf(project.toString(), sibling.toString()),
                places = PlaceBoard(
                    sets = listOf(ProjectSet("KMP apps", listOf(project.toString(), sibling.toString()))),
                ),
            ),
        )
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(installed)),
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

    private fun inBothThemes(model: IntegrationModel, assertions: () -> Unit) {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { RuleblendTheme(theme) { PlacePaletteColumn(model, onEditFile = {}) } }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            assertions()
        }
    }

    @Test fun `the palette lists the library by type and marks what this place already holds`() {
        inBothThemes(model()) {
            compose.onNodeWithText(strings.paletteLibrary.uppercase()).assertIsDisplayed()
            compose.onNodeWithText(strings.libRulesLabel.uppercase()).assertIsDisplayed()
            compose.onNodeWithText("Kotlin style").assertIsDisplayed()
            compose.onNodeWithText("Git flow").assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.libStatusSynced).assertIsDisplayed()
            // Installed here, so the row offers the way out; the offered one offers the way in.
            compose.onNodeWithContentDescription(strings.placeRemove).assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.placeInstall).assertIsDisplayed()
        }
    }

    @Test fun `only a favorite carries the star, and the palette offers no way to toggle it`() {
        repository.saveBlock(offered.copy(favorite = true))
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model(), onEditFile = {}) } }

        compose.onAllNodesWithText("★").assertCountEquals(1)
        compose.onAllNodes(hasClickAction() and hasText("★")).assertCountEquals(0)
    }

    @Test fun `installing from a palette row writes the block into this place`() {
        val model = model()
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.paletteSearch(2)).performTextInput("git")
        compose.onNodeWithContentDescription(strings.placeInstall).performClick()
        compose.waitForIdle()

        assertEquals(InstallStatus.SYNCED, model.statuses[offered.id])
        assertTrue(offered.content in project.resolve("AGENTS.md").readText())
    }

    @Test fun `a rule the rest of the set installed is recommended with its share of the set`() {
        sibling.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# transit-ios\n", regionFor(offered)),
        )

        inBothThemes(model()) {
            compose.onNodeWithText(strings.paletteRecommended.uppercase()).assertIsDisplayed()
            compose.onNodeWithText(strings.paletteInSet(1, 2, "KMP apps")).assertIsDisplayed()
        }
    }

    @Test fun `the files found in the project head the palette, above the library`() {
        inBothThemes(model()) {
            val found = compose.onNodeWithText(strings.paletteFound.uppercase()).fetchSemanticsNode().boundsInRoot
            val library = compose.onNodeWithText(strings.paletteLibrary.uppercase()).fetchSemanticsNode().boundsInRoot
            assertTrue(found.bottom <= library.top, "found files must come before the library")
        }
    }

    @Test fun `saving the place writes a library group holding exactly what is installed here`() {
        val model = model()
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.paletteSaveAsGroup).performClick()
        compose.onNodeWithText(strings.paletteSaveAsGroupHint(1)).assertIsDisplayed()
        compose.onAllNodesWithText("").onLast().performTextInput("KMP starter")
        compose.onNodeWithText(strings.actionSave).performClick()
        compose.waitUntil { model.catalog.objects.any { it.name == "kmp-starter" } }
        compose.waitForIdle()

        val group = repository.listGroups().single { it.name == "kmp-starter" }
        assertEquals(listOf(installed.id), group.blockIds)
        // A group is a library object, so the palette offers it back like any other.
        compose.onNodeWithText("kmp-starter").assertIsDisplayed()
    }

    @Test fun `an agent's own file is found under a header that names the agent, not a project`() {
        root.resolve("CLAUDE.md").writeText("Answer in Russian.\n")
        val model = model().also { it.agentTargets.first().let{ arg -> runBlocking { it.select(arg) } } }

        inBothThemes(model) {
            compose.onNodeWithText(strings.paletteFoundAgent.uppercase()).assertIsDisplayed()
            compose.onNodeWithText("CLAUDE.md").assertIsDisplayed()
        }
    }

    @Test fun `the collapsed setting closes the library on entry, and the header still opens it`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlacePaletteColumn(model(), onEditFile = {}, libraryExpandedByDefault = false)
            }
        }
        compose.onNodeWithText("Kotlin style").assertDoesNotExist()

        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Kotlin style").assertIsDisplayed()
    }

    @Test fun `the library header opens and closes every type section at once`() {
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model(), onEditFile = {}) } }
        compose.onNodeWithText("Kotlin style").assertIsDisplayed()

        compose.onNodeWithText("−").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Kotlin style").assertDoesNotExist()

        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Kotlin style").assertIsDisplayed()
    }

    @Test fun `hand-written text found in the project is shown as found, not as installed`() {
        inBothThemes(model()) {
            compose.onNodeWithText(strings.paletteFound.uppercase()).assertIsDisplayed()
            compose.onNodeWithText("AGENTS.md").assertIsDisplayed()
            compose.onNodeWithText(strings.paletteFoundLines(1)).assertIsDisplayed()
        }
    }

    @Test fun `the actions of a found file sit below its caption, not beside it`() {
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model(), onEditFile = {}) } }

        // Beside the caption both labels are long enough to squeeze the file name to one letter
        // per line; on their own line the caption keeps the column.
        val caption = compose.onNodeWithText(strings.paletteFoundLines(1)).fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithText(strings.intEditFile).fetchSemanticsNode().boundsInRoot
        assertTrue(edit.top >= caption.bottom)
    }

    @Test fun `adopting a found file saves its text to the library and leaves the file untouched`() {
        val model = model()
        val before = project.resolve("AGENTS.md").readText()
        val revision = model.writeRevision
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.actionAdopt).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.intAdoptTitle("AGENTS.md")).assertIsDisplayed()
        // Row button and dialog confirm carry the same label; the dialog is the one on top.
        compose.onAllNodesWithText(strings.actionAdopt).onLast().performClick()
        compose.waitUntil { model.writeRevision > revision }
        compose.waitForIdle()

        assertTrue(repository.listBlocks().any { "ledger-kmp" in it.content })
        // Replace is off, so the hand-written text stays exactly where the user put it.
        assertEquals(before, project.resolve("AGENTS.md").readText())
        // Adopt writes, so the set aggregate has to be rescanned like any other write here.
        assertTrue(model.writeRevision > revision)
    }

    @Test fun `the adopt form pins the rule to the project it was found in, and can be told otherwise`() {
        val model = model()
        val revision = model.writeRevision
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.actionAdopt).performClick()
        compose.waitForIdle()
        // The form opens on the project this file was found in — the answer that is right by default.
        compose.onNodeWithText(strings.fieldScope.uppercase()).assertIsDisplayed()
        compose.onNodeWithText(projectScopeLabel(project.toString())).assertIsDisplayed()
        compose.onAllNodesWithText(strings.actionAdopt).onLast().performClick()
        compose.waitUntil { model.writeRevision > revision }
        compose.waitForIdle()

        val pinned = configStore.load().ruleScopes
        assertEquals(1, pinned.size, "the adopted rule must belong to the project it came from")
        assertEquals(project.toAbsolutePath().normalize().toString(), pinned.values.single())
    }

    @Test fun `an adopted rule can be saved globally instead of to its project`() {
        val model = model()
        val revision = model.writeRevision
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.actionAdopt).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(projectScopeLabel(project.toString())).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.libGlobalScope).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(strings.actionAdopt).onLast().performClick()
        compose.waitUntil { model.writeRevision > revision }
        compose.waitForIdle()

        assertTrue(configStore.load().ruleScopes.isEmpty(), "a global rule is pinned to no project")
    }

    @Test fun `a found file with several sections is adopted as one block per section`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert(
                "# ledger-kmp\n\n## Naming\nUse lowerCamelCase.\n\n## Reviews\nOne reviewer minimum.\n",
                regionFor(installed),
            ),
        )
        val model = model()
        val before = repository.listBlocks().size
        val revision = model.writeRevision
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.actionAdopt).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(strings.actionAdopt).onLast().performClick()
        compose.waitUntil { model.writeRevision > revision }
        compose.waitForIdle()

        val blocks = repository.listBlocks()
        assertTrue(blocks.size > before)
        assertTrue(blocks.any { "lowerCamelCase" in it.content })
        assertTrue(blocks.any { "One reviewer minimum" in it.content })
    }

    @Test fun `the found row hands its own file to the editor`() {
        var edited: Path? = null
        val model = model()
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = { edited = it }) } }

        compose.onNodeWithText(strings.intEditFile).performClick()
        compose.waitForIdle()

        assertEquals(project.resolve("AGENTS.md"), edited)
    }

    @Test fun `a narrow palette drops the buttons under the name instead of cutting the name away`() {
        // A name long enough to matter: the column is where a rule is recognised, and "mul…" is not
        // a name. Installed here, so the row carries both a status badge and a write button.
        val long = installed.copy(id = "multiplatform-build", name = "Multiplatform build conventions")
        repository.saveBlock(long)
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(long)),
        )
        val model = model()
        var width by mutableStateOf(600.dp)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(width).fillMaxHeight()) { PlacePaletteColumn(model, onEditFile = {}) }
            }
        }
        val wide = compose.onNodeWithText(long.name).getUnclippedBoundsInRoot()
        val wideButton = compose.onNodeWithContentDescription(strings.placeRemove).getUnclippedBoundsInRoot()
        assertTrue(
            wide.bottom > wideButton.top && wide.top < wideButton.bottom,
            "with room to spare the name and its button are not on one line",
        )

        compose.runOnIdle { width = 160.dp }
        val narrow = compose.onNodeWithText(long.name).getUnclippedBoundsInRoot()
        val button = compose.onNodeWithContentDescription(strings.placeRemove).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(
            uiTarget("palette", "RULE:${long.id}", placeId(model.selected!!), "row"),
        ).getUnclippedBoundsInRoot()
        assertEquals(row.right - 6.dp, button.right, "wrapped controls must stay at the card's right edge")
        assertTrue(
            narrow.bottom <= button.top,
            "the name ends at ${narrow.bottom}, on the same line as a button starting at ${button.top}",
        )
        assertTrue(
            narrow.right - narrow.left >= 96.dp,
            "the name is squeezed to ${narrow.right - narrow.left} in a $width column",
        )
    }

    @Test fun `the search narrows the catalog to what matches`() {
        val model = model()
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { PlacePaletteColumn(model, onEditFile = {}) } }

        compose.onNodeWithText(strings.paletteSearch(2)).performTextInput("git")
        compose.waitForIdle()

        compose.onNodeWithText("Git flow").assertIsDisplayed()
        compose.onNodeWithText("Kotlin style").assertDoesNotExist()
    }

    /**
     * Same rule as the file preview beside it: the palette is one composable for every place, and a
     * scroll offset carried across a switch hides the header that names what the new place is being
     * offered. Pinned here because the reset lives in a `remember` key, which is easy to drop.
     */
    @Test fun `switching place opens the palette at its top, not at the previous place's offset`() {
        (1..40).forEach { repository.saveBlock(Block(id = "rule-$it", name = "Rule $it", content = "Line $it.")) }
        val model = model()
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(320.dp).height(240.dp)) { PlacePaletteColumn(model, onEditFile = {}) }
            }
        }

        val header = strings.paletteLibrary.uppercase()
        compose.onNodeWithText(header).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.waitForIdle()
        compose.onNodeWithText(header).assertDoesNotExist()

        compose.runOnIdle { model.projectTargets.first { it.dir == sibling }.let{ arg -> runBlocking { model.select(arg) } } }
        compose.waitForIdle()

        compose.onNodeWithText(header).assertIsDisplayed()
    }
}
