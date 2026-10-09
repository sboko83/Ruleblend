package dev.ruleblend.app.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.runBlocking
import org.junit.Rule

class LibraryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: LibraryModel

    private val swiftKey = LibraryObjectKey(LibraryObjectKind.RULE, "swift-style")

    /** Wide enough for all three columns, inside the test window the compose rule opens. */
    private val surface = 1000f

    private val places = listOf(
        LibraryPlaceUsage(
            id = "agent:claude-code",
            name = "Claude Code",
            kind = LibraryPlaceKind.AGENT,
            installs = mapOf(swiftKey to LibraryInstall(InstallStatus.SYNCED)),
        ),
        LibraryPlaceUsage(
            id = "project:/code/atlas",
            name = "atlas",
            kind = LibraryPlaceKind.PROJECT,
            installs = mapOf(swiftKey to LibraryInstall(InstallStatus.MODIFIED)),
        ),
    )

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-library-screen")
        repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.writeBlock(Block("swift-style", "Swift Style", description = "iOS conventions", content = "Use Swift."))
        repository.writeBlock(Block("kotlin-style", "Kotlin Style", description = "KMP conventions", content = "Use Kotlin."))
        repository.writeSkill(
            SkillSnapshot(
                Skill(
                    id = "code-review",
                    name = "code-review",
                    description = "Review checklist",
                    version = "2.4.0",
                    content = "# Review\n",
                    source = GitSkillSource("https://example.com/skills.git", "abc1234", "skills/review"),
                ),
                listOf(SkillFile("SKILL.md", "# Review\n".encodeToByteArray())),
            ),
        )
        repository.saveGroup(Group("mobile", "Mobile", blockIds = listOf("swift-style", "kotlin-style")))
        repository.syncAllGroup()
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = ConfigStore(root.resolve("config.json")),
            usageSource = { _, _ -> places },
            builtIns = builtInLibraryObjects(binary = null),
        ).also { m -> runBlocking { m.load() } }
        runBlocking { model.refreshUsage() }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `the inspector starts empty, and a facet clears what was read before it`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(model)
            }
        }

        // Nothing is picked on entry: whatever leads the catalog is a fact about the library, not
        // an object the reader asked to read.
        compose.onNodeWithText(EnStrings.libSelectObject).assertExists()

        compose.onNodeWithText("Swift Style").performClick()
        compose.onNodeWithText(EnStrings.libSelectObject).assertDoesNotExist()

        // A facet re-asks the question the list answers, so the answer beside it starts over.
        compose.onNodeWithText(EnStrings.libProjectScoped).performClick()
        compose.onNodeWithText(EnStrings.libSelectObject).assertExists()
    }

    @Test
    fun `left click selects one type and right click toggles types`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(model)
            }
        }

        // Rules are the default type filter. A left click on Skills replaces it.
        compose.onNodeWithTag("library-facet:type:SKILL").performClick()
        compose.onNodeWithText("code-review").assertExists()
        compose.onNodeWithText("Swift Style").assertDoesNotExist()

        // Secondary clicks keep the existing multi-type filter behavior.
        compose.onNodeWithTag("library-facet:type:RULE").performMouseInput { rightClick() }
        compose.onNodeWithText("Swift Style").assertExists()
        compose.onNodeWithText("code-review").assertExists()
        compose.onNodeWithTag("library-facet:type:SKILL").performMouseInput { rightClick() }
        compose.onNodeWithText("Swift Style").assertExists()
        compose.onNodeWithText("code-review").assertDoesNotExist()
    }

    @Test
    fun `the built-in skill and MCP entry are readable but not the user's to change`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(model)
            }
        }

        // The query leaves the two built-ins in the list and nothing of the user's.
        compose.onNode(hasSetTextAction()).performTextInput("ruleblend")
        // Bulk install, group and export act on library objects; a built-in offers no tick.
        compose.onAllNodesWithTag(LibraryRowMarkTag).assertCountEquals(0)

        compose.runOnIdle {
            model.selectCatalogObject(LibraryObjectKey(LibraryObjectKind.SKILL, BUILTIN_SKILL_ID))
        }

        compose.onNodeWithText(EnStrings.libBuiltInManaged).assertExists()
        compose.onNodeWithTag(LibraryInspectorBodyTag).assertExists()
        // The two actions every other object carries at the bottom of the inspector.
        compose.onNodeWithContentDescription(EnStrings.libEditFocus).assertDoesNotExist()
        compose.onNodeWithContentDescription(EnStrings.actionDelete).assertDoesNotExist()
    }

    @Test
    fun `search selects a real object and opens its existing editor`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(model)
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        compose.onNodeWithText("Swift Style").performClick()
        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithText("INSTRUCTION (MARKDOWN)").assertExists()
        compose.onNodeWithText("Use Swift.").assertExists()
    }

    @Test
    fun `a rule opened from Projects enters the editor and returns there`() {
        var returned = false
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(
                    model = model,
                    openEditorKey = swiftKey,
                    editorBackLabel = EnStrings.navPlace,
                    onEditorBack = { returned = true },
                )
            }
        }

        compose.onNodeWithText("Use Swift.").assertExists()
        compose.onNodeWithText("← ${EnStrings.navPlace}").performClick()

        assertTrue(returned)
    }

    @Test
    fun `side columns give way to the list as the window narrows, in both themes`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var width by mutableStateOf(1440.dp)
        compose.setContent {
            RuleblendTheme(theme) {
                Box(Modifier.width(width).fillMaxHeight()) { LibraryScreen(model) }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        compose.onAllNodesWithText("Swift Style").onFirst().performClick()

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            // Calculated width, then the minimum window minus the rail: three columns either way.
            listOf(1440.dp, 908.dp).forEach { wide ->
                compose.runOnIdle { width = wide }
                compose.onNodeWithText("TYPES").assertExists()
                compose.onNodeWithText("INSTALLED IN \u00b7 2").assertExists()
            }
            // Narrower than all three: facets go first, the inspector and the list stay.
            compose.runOnIdle { width = 560.dp }
            compose.onNodeWithText("TYPES").assertDoesNotExist()
            compose.onNodeWithText("INSTALLED IN \u00b7 2").assertExists()
            compose.onNodeWithText("By group").assertExists()
        }
    }

    @Test
    fun `catalog renders with the same controls in both themes`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            RuleblendTheme(theme) {
                LibraryScreen(model)
            }
        }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText("By group").assertExists()
            compose.onNodeWithText("By type").assertExists()
        }
    }

    @Test
    fun `available update facet opens directly onto changed imported skills`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(
                    model = model,
                    sourceUpdateIds = setOf("code-review"),
                    sourceUpdatesChecked = true,
                )
            }
        }

        compose.onNodeWithText(EnStrings.libUpdateAvailable).performClick()

        compose.onAllNodesWithText("code-review").onFirst().assertExists()
        compose.onNodeWithText("Swift Style").assertDoesNotExist()
    }

    @Test
    fun `fork inspector shows its base and compares with newer upstream`() {
        val imported = requireNotNull(repository.loadSkill("code-review"))
        repository.forkSkill("code-review", "code-review-changed", "code-review-changed", "# Local review\n")
        repository.writeSkill(
            SkillSnapshot(
                imported.copy(version = "2.5.0", content = "# Upstream review\n", source = imported.source?.copy(revision = "def5678")),
                listOf(SkillFile("SKILL.md", "# Upstream review\n".encodeToByteArray())),
            ),
        )
        runBlocking { model.load() }

        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { LibraryScreen(model) }
        }
        compose.runOnIdle {
            model.selectCatalogObject(LibraryObjectKey(LibraryObjectKind.SKILL, "code-review-changed"))
        }

        compose.onNodeWithText(EnStrings.libForkBaseTitle).assertExists()
        compose.onNodeWithText("skills/review").assertExists()
        compose.onNodeWithText("@ abc1234").assertExists()
        compose.onNodeWithText(EnStrings.libForkUpstreamAhead).assertExists()
        compose.onNodeWithTag(LibraryCompareUpstreamTag).performClick()
        // Compare opens on the aligned difference; the drafts live behind its edit mode.
        compose.onNodeWithTag(CompareModeEditTag).performClick()

        compose.onNodeWithTag(CompareRightDraftTag).assertTextEquals("# Upstream review\n")
    }

    @Test
    fun `inspector answers where a rule is installed and opens the place`() {
        var opened: String? = null
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            RuleblendTheme(theme) {
                LibraryScreen(model, onOpenPlace = { opened = it })
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        compose.onAllNodesWithText("Swift Style").onFirst().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { model.history.isNotEmpty() }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText("INSTALLED IN · 2").assertExists()
            compose.onNodeWithText("Claude Code").assertExists()
            compose.onNodeWithText("synced").assertExists()
            compose.onNodeWithText("modified").assertExists()
            // The library's own commit is the version history: no separate bookkeeping.
            compose.onNodeWithText("Save block swift-style", substring = true).assertExists()
        }

        compose.onNodeWithText("atlas").performClick()
        compose.runOnIdle { assertEquals("project:/code/atlas", opened) }
    }

    @Test
    fun `marking a row opens the bulk bar in both themes and its actions reach the model`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            RuleblendTheme(theme) {
                LibraryScreen(model)
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Kotlin")
        compose.onAllNodesWithTag(LibraryRowMarkTag).onFirst().performClick()
        compose.runOnIdle {
            assertEquals(setOf(LibraryObjectKey(LibraryObjectKind.RULE, "kotlin-style")), model.marked)
        }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText("1 selected").assertExists()
            compose.onNodeWithContentDescription("Install into…").assertExists()
            compose.onNodeWithContentDescription("Export").assertExists()
        }

        compose.onNodeWithContentDescription("Add to group…").performClick()
        compose.onNodeWithText("Add to group").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("1 selected").assertExists()
    }

    @Test
    fun `an unused object says so and an imported skill shows its origin`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                LibraryScreen(model)
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("Kotlin")
        compose.onAllNodesWithText("Kotlin Style").onFirst().performClick()
        compose.onNodeWithText("unused").assertExists()
        compose.onNodeWithText("Not installed anywhere.").assertExists()

        compose.onNode(hasSetTextAction()).performTextReplacement("review")
        compose.onAllNodesWithText("code-review").onFirst().performClick()
        compose.onNodeWithText("https://example.com/skills.git").assertExists()
        compose.onNodeWithText("@ abc1234").assertExists()
        compose.onNodeWithContentDescription("Fork to edit").assertExists()
    }

    @Test
    fun `the search field clears in one click`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(surface.dp).fillMaxHeight()) { LibraryScreen(model) }
            }
        }

        // No mark on an empty field: it would be a control that does nothing.
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        compose.onNodeWithContentDescription("Clear search").assertExists()

        compose.onNodeWithContentDescription("Clear search").performClick()

        // The mark goes with the text it cleared, and the whole library is listed again.
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        compose.onAllNodesWithText("Kotlin Style").onFirst().assertExists()
    }

    @Test
    fun `both side columns are dragged to a new width, and the width outlives the drag`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(surface.dp).fillMaxHeight()) { LibraryScreen(model) }
            }
        }

        // The facets pane ends at its own width; its divider is the last few pixels of it.
        drag(from = model.columnWidths.libraryFacets.toFloat() - 3f, by = 30f)
        compose.runOnIdle {
            assertTrue(model.columnWidths.libraryFacets > 240, "facets stayed at ${model.columnWidths.libraryFacets}")
        }

        // The inspector is dragged by its leading edge, so widening it means moving left.
        drag(from = surface - model.columnWidths.libraryInspector + 3f, by = -30f)
        compose.runOnIdle {
            assertTrue(model.columnWidths.libraryInspector > 340, "inspector stayed at ${model.columnWidths.libraryInspector}")
        }
    }

    private fun drag(from: Float, by: Float) {
        compose.onRoot().performMouseInput {
            moveTo(Offset(from, 300f))
            press()
            moveBy(Offset(by, 0f))
            release()
        }
        compose.waitForIdle()
    }

    @Test
    fun `the inspector heads an object with its mark, name and version on one line`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(surface.dp).fillMaxHeight()) { LibraryScreen(model) }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        compose.onAllNodesWithText("Swift Style").onFirst().performClick()

        // The letter beside the name already says this is a rule; the word repeated it.
        compose.onNodeWithText("rule").assertDoesNotExist()

        val name = compose.onAllNodesWithText("Swift Style").onLast().getUnclippedBoundsInRoot()
        // The list row carries a version too; the inspector's is the one furthest to the right.
        val versions = compose.onAllNodesWithText("v1")
        val version = List(versions.fetchSemanticsNodes().size) { versions[it].getUnclippedBoundsInRoot() }
            .maxBy { it.left.value }
        assertTrue(
            version.top < name.bottom && version.bottom > name.top,
            "the version was pushed off the title line: $version against $name",
        )
        val mark = compose.onNodeWithTag(LibraryInspectorMarkTag).getUnclippedBoundsInRoot()
        val markCentre = (mark.top + mark.bottom) / 2
        val nameCentre = (name.top + name.bottom) / 2
        assertTrue(
            abs((markCentre - nameCentre).value) <= 2f,
            "the type mark sits at $markCentre against a name centred at $nameCentre",
        )
    }
}
