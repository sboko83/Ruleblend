package dev.ruleblend.app.library

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * The inspector is read before anything is done to an object, so the two things a reader reaches for
 * from it are one click away: the text opens the editor it would be changed in, and a revision opens
 * what that revision actually did. A history that only lists dates says a rule changed, never how.
 */
class InspectorHistoryTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var model: LibraryModel
    private val strings = EnStrings
    private val revisionRow = SemanticsMatcher("revision row") {
        runCatching { it.config[SemanticsProperties.TestTag] }.getOrNull()
            ?.startsWith("$LibraryRevisionRowTag:") == true
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-inspector-history")
        val repository = LibraryRepository(root.resolve("library"))
        repository.init()
        // Two commits, so the newest revision has a parent to be a diff against.
        repository.saveBlock(Block("swift-style", "Swift Style", description = "iOS conventions", content = "Use Swift.\n"))
        repository.saveBlock(Block("swift-style", "Swift Style", description = "iOS conventions", content = "Use Swift.\nUse it well.\n"))
        repository.saveGroup(Group("quality", "Quality", blockIds = listOf("swift-style")))
        repository.saveProfile(Profile("testing", "Testing", blockIds = listOf("swift-style"), groupIds = listOf("quality")))
        repository.saveProfile(Profile("testing", "Testing", blockIds = listOf("swift-style"), groupIds = listOf("quality", "all")))
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

    private fun openInspector() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { LibraryScreen(model) }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        compose.onAllNodesWithText("Swift Style").onFirst().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the body of the inspector opens the editor`() {
        openInspector()

        compose.onNodeWithTag(LibraryInspectorBodyTag).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.libInstruction.uppercase()).assertExists()
    }

    @Test
    fun `a revision opens its diff, and the diff says what changed`() {
        openInspector()

        // The history is read off the UI thread once an object is picked, and nothing is picked
        // before the click above: the rows appear after the read, not with the inspector.
        compose.waitUntil { compose.onAllNodes(revisionRow).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(revisionRow).onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RevisionDiffBodyTag).assertExists()
        // The line the second save added, as an added line of the diff.
        compose.onNodeWithText("+ Use it well.").assertExists()
    }

    @Test
    fun `a profile inspector lists groups and opens its version diff`() {
        model.selectCatalogObject(LibraryObjectKey(LibraryObjectKind.PROFILE, "testing"))
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { LibraryScreen(model) }
        }
        compose.waitUntil { compose.onAllNodes(revisionRow).fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodesWithText("Quality").onFirst().assertExists()
        compose.onAllNodes(revisionRow).onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RevisionDiffBodyTag).assertExists()
    }

    @Test
    fun `a profile opened directly shows the grouped composition editor`() {
        val profile = LibraryObjectKey(LibraryObjectKind.PROFILE, "testing")
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { LibraryScreen(model, openEditorKey = profile) }
        }
        compose.waitForIdle()

        compose.onNodeWithText(strings.libProfileContents.uppercase()).assertExists()
    }
}
