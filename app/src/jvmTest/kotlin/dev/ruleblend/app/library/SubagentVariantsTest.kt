package dev.ruleblend.app.library

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.integration.ZCodeAdapter
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

/**
 * The per-assistant section of the subagent editor. One subagent, one field per assistant tunable:
 * editing Claude Code's model must not reach Codex, and an assistant without subagent support gets
 * no fields at all. Only assistants with values or an installed copy are listed; the rest are added
 * from a menu, and the fields shown are those of the one assistant picked in the list.
 */
class SubagentVariantsTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: LibraryModel
    private var scanned = emptyList<LibraryPlaceUsage>()

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
            usageSource = { _, _ -> scanned },
            subagentAgents = listOf(ClaudeCodeAdapter(home), CodexAdapter(home), PiAdapter(home), ZCodeAdapter(home)).subagentAgentFields(),
        ).also { loaded -> runBlocking { loaded.load() } }
        model.selectBlock(subagent.id)
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

    private fun add(agentId: String) {
        compose.onNodeWithTag(SUBAGENT_AGENT_ADD_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("$SUBAGENT_AGENT_ADD_TAG-$agentId").performClick()
        compose.waitForIdle()
    }

    private fun assertFields(agentId: String, vararg fields: Pair<String, Int>) = fields.forEach { (field, count) ->
        compose.onAllNodesWithTag(subagentFieldTag(agentId, field)).assertCountEquals(count)
    }

    @Test
    fun `only assistants with values are listed, and the first one is open`() {
        open()
        compose.onAllNodesWithTag(subagentAgentTag("claude-code")).assertCountEquals(1)
        listOf("codex", "pi", "zcode").forEach { compose.onAllNodesWithTag(subagentAgentTag(it)).assertCountEquals(0) }
        compose.onNodeWithTag(subagentFieldTag("claude-code", "model")).assertTextEquals("haiku")
        compose.onNodeWithTag(subagentFieldTag("claude-code", "tools")).assertTextEquals("Bash, Read")
        // An unset field is present but empty: its prompt is all the node carries.
        assertFields("claude-code", "color" to 1)
        assertFields("codex", "model" to 0)
    }

    @Test
    fun `an added assistant shows the fields its own definition file understands`() {
        open()
        add("codex")
        // Codex has no tool field of its own; the assistant that was open before is no longer shown.
        assertFields("codex", "model" to 1, "tools" to 0)
        assertFields("claude-code", "model" to 0)
        add("pi")
        assertFields("pi", "model" to 1, "tools" to 1, "thinking" to 1)
        // Codex was added but left empty, so moving on drops it from the list again.
        compose.onAllNodesWithTag(subagentAgentTag("codex")).assertCountEquals(0)
        add("zcode")
        assertFields("zcode", "model" to 1, "tools" to 1, "color" to 1)
    }

    @Test
    fun `editing one assistant leaves the others untouched`() {
        open()
        add("codex")
        compose.onNodeWithTag(subagentFieldTag("codex", "model")).performTextReplacement("gpt-5-codex")
        compose.waitForIdle()

        val draft = requireNotNull(model.draft)
        assertEquals("gpt-5-codex", draft.variant("codex")["model"])
        assertEquals(SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read")), draft.variant("claude-code"))
        // Once it has a value, it stays listed after another assistant is opened.
        compose.onNodeWithTag(subagentAgentTag("claude-code")).performClick()
        compose.onAllNodesWithTag(subagentAgentTag("codex")).assertCountEquals(1)
    }

    @Test
    fun `clearing a field drops it instead of installing an empty value`() {
        open()
        compose.onNodeWithTag(subagentFieldTag("claude-code", "tools")).performTextReplacement("")
        compose.waitForIdle()

        val draft = requireNotNull(model.draft)
        assertNull(draft.variant("claude-code")["tools"])
        assertEquals("haiku", draft.variant("claude-code")["model"])
    }

    @Test
    fun `reset returns an assistant to its own defaults`() {
        open()
        compose.onNodeWithTag(SUBAGENT_AGENT_RESET_TAG).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(requireNotNull(model.draft).variant("claude-code").isEmpty)
        assertFields("claude-code", "model" to 1)
    }

    @Test
    fun `an assistant holding a copy is listed even with nothing tuned`() {
        val key = LibraryObjectKey(LibraryObjectKind.SUBAGENT, subagent.id)
        scanned = listOf(
            LibraryPlaceUsage("agent:zcode", "ZCode", LibraryPlaceKind.AGENT,
                mapOf(key to LibraryInstall(InstallStatus.SYNCED, agents = setOf("zcode")))),
        )
        runBlocking { model.refreshUsage() }
        open()

        compose.onAllNodesWithTag(subagentAgentTag("zcode")).assertCountEquals(1)
        compose.onNodeWithTag(subagentAgentTag("zcode")).performClick()
        compose.waitForIdle()
        assertFields("zcode", "model" to 1, "tools" to 1, "color" to 1)
    }
}
