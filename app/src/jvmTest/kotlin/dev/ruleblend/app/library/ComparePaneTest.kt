package dev.ruleblend.app.library

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextReplacement
import dev.ruleblend.app.compare.diffHunkCellTag
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.compare.LineDiff
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The compare dialog is operated through named controls, not its translated labels or the hunk
 * layout. The tags are consequently a small but deliberate UI contract for its core actions.
 */
class ComparePaneTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: LibraryModel
    private lateinit var source: Block
    private lateinit var firstCandidate: Block
    private lateinit var secondCandidate: Block

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-compare-pane")
        repository = LibraryRepository(root.resolve("library"))
        repository.init()
        source = Block(id = "source", name = "Source", content = "shared\nleft\n")
        firstCandidate = Block(id = "first", name = "First", content = "shared\nright\n")
        secondCandidate = Block(id = "second", name = "Second", content = "shared\nother\n")
        listOf(source, firstCandidate, secondCandidate).forEach(repository::saveBlock)
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = ConfigStore(root.resolve("config.json")),
            usageSource = { _, _ -> emptyList() },
        ).also { loaded -> runBlocking { loaded.load() } }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun open() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                ComparePane(model, CompareSubject.from(source), onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `requested candidate opens on the right`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                ComparePane(
                    model = model,
                    source = CompareSubject.from(source),
                    initialCandidateKey = LibraryObjectKey(LibraryObjectKind.RULE, secondCandidate.id),
                    onDismiss = {},
                )
            }
        }

        edit()

        compose.onNodeWithTag(CompareRightDraftTag).assertTextEquals(secondCandidate.content)
    }

    /** The drafts are the edit surface; the dialog opens on the diff, so a text assertion goes there. */
    private fun edit() {
        compose.onNodeWithTag(CompareModeEditTag).performClick()
        compose.waitForIdle()
    }

    private fun select(candidate: Block) {
        compose.onNodeWithTag(CompareCandidatePickerTag).performClick()
        compose.onNodeWithTag(compareCandidateTag(LibraryObjectKey(LibraryObjectKind.RULE, candidate.id))).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `candidate tag replaces the right draft`() {
        open()

        select(secondCandidate)
        edit()

        compose.onNodeWithTag(CompareRightDraftTag).assertTextEquals(secondCandidate.content)
    }

    @Test
    fun `left arrow copies the right hunk into the left draft`() {
        open()
        select(firstCandidate)

        compose.onNodeWithTag(compareCopyRightToLeftTag(0)).performClick()
        compose.waitForIdle()
        edit()

        compose.onNodeWithTag(CompareLeftDraftTag).assertTextEquals(firstCandidate.content)
    }

    @Test
    fun `right arrow copies the left hunk into the right draft`() {
        open()
        select(firstCandidate)

        compose.onNodeWithTag(compareCopyLeftToRightTag(0)).performClick()
        compose.waitForIdle()
        edit()

        compose.onNodeWithTag(CompareRightDraftTag).assertTextEquals(source.content)
    }

    @Test
    fun `undo tag restores the draft before a hunk transfer`() {
        open()
        select(firstCandidate)
        compose.onNodeWithTag(compareCopyRightToLeftTag(0)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CompareUndoTag).performClick()
        compose.waitForIdle()
        edit()

        compose.onNodeWithTag(CompareLeftDraftTag).assertTextEquals(source.content)
    }

    @Test
    fun `switching candidate keeps the work already moved into the left draft`() {
        open()
        select(firstCandidate)
        compose.onNodeWithTag(compareCopyRightToLeftTag(0)).performClick()
        compose.waitForIdle()

        select(secondCandidate)
        edit()

        compose.onNodeWithTag(CompareLeftDraftTag).assertTextEquals(firstCandidate.content)
        compose.onNodeWithTag(CompareRightDraftTag).assertTextEquals(secondCandidate.content)
    }

    @Test
    fun `reset tag returns a side to the body it was saved from`() {
        open()
        select(firstCandidate)
        compose.onNodeWithTag(compareCopyRightToLeftTag(0)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CompareResetLeftTag).performClick()
        compose.waitForIdle()
        edit()

        compose.onNodeWithTag(CompareLeftDraftTag).assertTextEquals(source.content)
        compose.onNodeWithTag(CompareResetLeftTag).assertIsNotEnabled()
    }

    @Test
    fun `saving a side leaves nothing further to save or reset`() {
        open()
        select(firstCandidate)
        compose.onNodeWithTag(compareCopyRightToLeftTag(0)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(CompareSaveLeftTag).performClick()
        compose.waitUntil { repository.loadBlock(source.id)?.content == firstCandidate.content }
        compose.waitUntil {
            compose.onNodeWithTag(CompareResetLeftTag).fetchSemanticsNode().config
                .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        compose.waitForIdle()

        compose.onNodeWithTag(CompareSaveLeftTag).assertIsNotEnabled()
        compose.onNodeWithTag(CompareResetLeftTag).assertIsNotEnabled()
        edit()
        compose.onNodeWithTag(CompareLeftDraftTag).assertTextEquals(firstCandidate.content)
    }

    @Test
    fun `save both tag persists each changed draft`() {
        open()
        select(firstCandidate)
        compose.onNodeWithTag(compareCopyRightToLeftTag(0)).performClick()
        edit()
        compose.onNodeWithTag(CompareRightDraftTag).performTextReplacement("shared\nsaved-right\n")

        compose.onNodeWithTag(CompareSaveBothTag).performClick()
        compose.waitUntil {
            repository.loadBlock(source.id)?.content == firstCandidate.content &&
                repository.loadBlock(firstCandidate.id)?.content == "shared\nsaved-right\n"
        }

        assertEquals(firstCandidate.content, repository.loadBlock(source.id)?.content)
        assertEquals("shared\nsaved-right\n", repository.loadBlock(firstCandidate.id)?.content)
    }

    @Test
    fun `right click on a difference saves that side as a new rule`() {
        open()
        select(firstCandidate)

        compose.onNodeWithTag(diffHunkCellTag(0, left = false)).performMouseInput { rightClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Save as rule…").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CompareSaveRuleNameTag).assertTextEquals("right")
        compose.onNodeWithTag(CompareSaveRuleNameTag).performTextReplacement("Picked")
        // The compare dialog has its own confirm underneath; the save dialog's is the one that says Save.
        compose.onAllNodesWithTag("dialog-confirm").filterToOne(hasText("Save")).performClick()
        compose.waitUntil { repository.listBlocks().size == 4 }

        // The name goes through the configured name format, so the body is what identifies the rule.
        val saved = repository.listBlocks().single { it.id !in setOf(source.id, firstCandidate.id, secondCandidate.id) }
        assertEquals("right", saved.content)
        assertEquals(BlockType.RULE, saved.type)
        assertEquals(source.content, repository.loadBlock(source.id)?.content)
        assertEquals(firstCandidate.content, repository.loadBlock(firstCandidate.id)?.content)
    }

    @Test
    fun `a pure addition gives its text whichever side was clicked`() {
        val hunk = LineDiff.between("a\n", "a\nadded\n").single()

        assertEquals("added", hunkSideText(hunk, left = true))
        assertEquals("added", hunkSideText(hunk, left = false))
    }

    @Test
    fun `suggested name drops markdown marks and blank lines`() {
        assertEquals("Git safety", suggestedRuleName("\n## Git safety\n- never force-push"))
        assertEquals("Use val", suggestedRuleName("- **Use val**:"))
        assertEquals("", suggestedRuleName("\n  \n"))
    }
}
