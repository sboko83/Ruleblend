package dev.ruleblend.app.library

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.integration.subagentAgentFields
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The per-assistant section of the subagent editor. One subagent, one field per assistant tunable:
 * editing Claude Code's model must not reach Codex, and an assistant without subagent support gets
 * no fields at all.
 */
class SubagentVariantsTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: LibraryModel

    private val subagent = Block(
        id = "reviewer",
        name = "Reviewer",
        description = "Reviews changes",
        type = BlockType.SUBAGENT,
        variants = mapOf("claude-code" to SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read"))),
        content = "# Review\n",
    )

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-subagent-variants")
        repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.saveBlock(subagent)
        val home = root.resolve("home")
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = ConfigStore(root.resolve("config.json")),
            usageSource = { _, _ -> emptyList() },
            subagentAgents = listOf(ClaudeCodeAdapter(home), CodexAdapter(home), PiAdapter(home)).subagentAgentFields(),
        ).also { loaded -> runBlocking { loaded.load() } }
        model.selectBlock(subagent.id)
        open()
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun open() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                EditorPane(
                    model = model,
                    onDeleteBlock = {},
                    onDeleteGroup = {},
                    onDeleteProfile = {},
                    onSplitBlock = {},
                    onCompare = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `each assistant shows the fields its own definition file understands`() {
        compose.onNodeWithTag(subagentFieldTag("claude-code", "model")).assertTextEquals("haiku")
        compose.onNodeWithTag(subagentFieldTag("claude-code", "tools")).assertTextEquals("Bash, Read")
        // An unset field is present but empty: its prompt is all the node carries.
        compose.onAllNodesWithTag(subagentFieldTag("claude-code", "color")).assertCountEquals(1)
        compose.onAllNodesWithTag(subagentFieldTag("codex", "model")).assertCountEquals(1)
        // Codex has no tool field of its own, and Pi hosts no subagents at all.
        compose.onAllNodesWithTag(subagentFieldTag("codex", "tools")).assertCountEquals(0)
        compose.onAllNodesWithTag(subagentFieldTag("pi", "model")).assertCountEquals(0)
    }

    @Test
    fun `editing one assistant leaves the others untouched`() {
        compose.onNodeWithTag(subagentFieldTag("codex", "model")).performTextReplacement("gpt-5-codex")
        compose.waitForIdle()

        val draft = requireNotNull(model.draft)
        assertEquals("gpt-5-codex", draft.variant("codex")["model"])
        assertEquals(SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read")), draft.variant("claude-code"))
    }

    @Test
    fun `clearing a field drops it instead of installing an empty value`() {
        compose.onNodeWithTag(subagentFieldTag("claude-code", "tools")).performTextReplacement("")
        compose.waitForIdle()

        val draft = requireNotNull(model.draft)
        assertNull(draft.variant("claude-code")["tools"])
        assertEquals("haiku", draft.variant("claude-code")["model"])
    }
}
