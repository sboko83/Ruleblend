package dev.ruleblend.app.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.ClipboardEnvironment
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * The catalog list behaves like every other list of documents: one click selects, two open. And the
 * reserved group is the one row that opens nothing — it is recomputed from the library, so an
 * editor, a history and an install count would all be about objects other than it.
 */
class LibraryCatalogRowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var model: LibraryModel
    private val strings = EnStrings

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-catalog-row")
        val repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.saveBlock(Block("swift-style", "Swift Style", description = "iOS conventions", content = "Use Swift.\n", heading = "Swift conventions", headingLevel = 3))
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = ConfigStore(root.resolve("config.json")),
            usageSource = { _, _ -> emptyList() },
        ).also { m -> runBlocking { m.load() } }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun open(query: String? = null) {
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { LibraryScreen(model) } }
        query?.let { compose.onNode(hasSetTextAction()).performTextInput(it) }
        compose.waitForIdle()
    }

    @Test fun `a double click on a row opens that object's editor`() {
        open(query = "Swift")

        compose.onAllNodesWithText("Swift Style").onFirst().performMouseInput { doubleClick() }
        compose.waitForIdle()

        compose.onNodeWithText(strings.libInstruction.uppercase()).assertExists()
    }

    @Test fun `preview context menu copies the heading and opens the editor`() {
        val previousCopy = ClipboardEnvironment.copy
        var copied: String? = null
        ClipboardEnvironment.copy = { copied = it; true }
        try {
            open(query = "Swift")
            compose.onAllNodesWithText("Swift Style").onFirst().performClick()
            compose.onNodeWithTag(LibraryInspectorBodyTag).performMouseInput { rightClick() }
            compose.onNodeWithText(strings.setCopy).performClick()
            assertEquals("### Swift conventions\n\nUse Swift.", copied)
            compose.onNodeWithTag(LibraryInspectorBodyTag).performMouseInput { rightClick() }
            compose.onAllNodesWithText(strings.libEditFocus).onLast().performClick()
            compose.onNodeWithText(strings.libInstruction.uppercase()).assertExists()
        } finally {
            ClipboardEnvironment.copy = previousCopy
        }
    }

    @Test fun `the reserved group states what it is and stops there`() {
        // Selected through the model: the catalog opens filtered to rules, and how the reserved group
        // is reached is not what this test is about.
        model.selectCatalogObject(LibraryObjectKey(LibraryObjectKind.GROUP, ALL_GROUP_ID))
        open()

        // What it is, and how many objects it holds.
        compose.onNodeWithText(strings.libAutoMaintained).assertIsDisplayed()
        compose.onNodeWithText(strings.libMembersCount(1).uppercase()).assertIsDisplayed()
        // Nothing that belongs to an object that can be changed: no history, no install sites, no
        // Edit and no Delete.
        compose.onAllNodesWithText(strings.libHistory.uppercase()).assertCountEquals(0)
        compose.onNodeWithContentDescription(strings.libEditFocus).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.actionDelete).assertDoesNotExist()
    }

    @Test fun `an active filter chip does not rest on the divider under it`() {
        // The catalog opens filtered to rules, so a chip is on screen from the start.
        open()

        val strip = compose.onNodeWithTag(LibraryActiveFiltersTag).getUnclippedBoundsInRoot()
        val chip = compose.onAllNodesWithText(strings.libTypeRule + "  ×").onFirst().getUnclippedBoundsInRoot()
        val gap = strip.bottom - chip.bottom
        assertTrue(gap >= 6.dp, "the chip ends " + gap + " above the divider")
    }
}
