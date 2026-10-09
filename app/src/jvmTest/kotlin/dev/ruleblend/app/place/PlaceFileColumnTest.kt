package dev.ruleblend.app.place

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.RuStrings
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.LegacyMarkers
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.SkillDirectory
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStateStore
import dev.ruleblend.core.integration.SubagentSupport
import dev.ruleblend.core.integration.ClaudeCodeSubagentFormat
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.integration.WrappedRun
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.Target
import dev.ruleblend.mcp.install.McpAgentInstaller
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpInstallRecord
import dev.ruleblend.mcp.install.McpServersJson
import dev.ruleblend.mcp.install.McpStateStore
import dev.ruleblend.mcp.MCP_ENTRY_ENV_KEY
import dev.ruleblend.mcp.MCP_SERVER_NAME
import dev.ruleblend.mcp.skill.BundledSkill
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.translate.TranslationStatus
import dev.ruleblend.core.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import dev.ruleblend.core.integration.InstallStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * The centre of Place in the states a real project file can be in: absent, legacy markers, partial
 * ownership and fully owned. Both themes, because the design contract treats them as equals.
 */
class PlaceFileColumnTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val strings = EnStrings
    /** Display names of the agents a multi-agent place is built from, as the copies are labelled. */
    private val agentNames = mapOf("claude-code" to "Claude Code", "codex" to "Codex")
    private val block = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")

    private fun agent(
        home: Path,
        skills: Boolean = false,
        subagents: Boolean = false,
        agentId: String = "claude-code",
        agentName: String = "Claude Code",
        skillRoots: List<Path>? = null,
    ) = object : AgentAdapter {
        override val id = agentId
        override val name = agentName
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
        override fun projectRedirectFile(projectDir: Path): Path = projectDir.resolve("CLAUDE.md")
        override fun projectSkillsDirectory(projectDir: Path): Path? =
            if (skills) projectDir.resolve(".claude").resolve("skills") else null
        override fun projectSkillDirectories(projectDir: Path): List<SkillDirectory> =
            skillRoots?.mapIndexed { index, path -> SkillDirectory("root-$index", path) }
                ?: super.projectSkillDirectories(projectDir)
        override val subagentSupport: SubagentSupport =
            if (subagents) SubagentSupport.SUPPORTED else SubagentSupport.UNSUPPORTED
        override val subagentFormat = if (subagents) ClaudeCodeSubagentFormat else null
        override fun projectSubagentDirectory(projectDir: Path): Path? =
            if (subagents) projectDir.resolve(".claude").resolve("agents") else null
    }

    /**
     * An agent that reads a project MCP config, so the place has an MCP address to answer for.
     * [entry] is what that config already holds, which is what a drift is read against.
     */
    private fun mcpInstaller(entry: McpServerConfig? = null, id: String = "claude-code") = object : McpAgentInstaller {
        override val agentId = id
        override fun supports(config: McpServerConfig) = true
        override fun configFile(target: Target): Path? = (target as? ProjectTarget)?.dir?.resolve(".mcp.json")
        override fun names(text: String): List<String> = McpServersJson.names(text)
        override fun entryText(text: String, name: String): String? = McpServersJson.entryText(text, name)
        override fun install(target: Target, name: String, config: McpServerConfig) = Unit
        override fun remove(target: Target, name: String) = Unit
        override fun installed(target: Target, name: String): McpServerConfig? = entry
    }

    @BeforeTest
    fun setUp() {
        root = Path.of(Files.createTempDirectory("ruleblend-place-file").projectKey())
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(block)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    /** Builds the model over the current on-disk state and selects the project place. */
    private fun model(
        mcp: Boolean = false,
        skills: Boolean = false,
        subagents: Boolean = false,
        skillRoots: List<Path>? = null,
        mcpEntry: McpServerConfig? = null,
        /** Several agents, each holding its own copy of the entry — the case a drift can come apart in. */
        mcpEntries: Map<String, McpServerConfig> = emptyMap(),
    ): IntegrationModel = IntegrationModel(
        repository = repository,
        configStore = configStore,
        agents =
            if (mcpEntries.isEmpty()) listOf(agent(root, skills, subagents, skillRoots = skillRoots))
            else mcpEntries.keys.map { id -> agent(root, skills, subagents, id, agentNames.getValue(id), skillRoots) },
        backupService = BackupService(root.resolve("backups")),
        service = IntegrationService(blockResolver = repository::loadBlock),
        mcpService = McpInstallService(
            installers = when {
                mcpEntries.isNotEmpty() -> mcpEntries.map { (id, entry) -> mcpInstaller(entry, id) }
                mcp -> listOf(mcpInstaller(mcpEntry))
                else -> emptyList()
            },
            state = McpStateStore(root),
        ),
        skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
        subagentService = SubagentInstallService(SubagentInstallStateStore(root)),
    ).also { model ->
        runBlocking { model.load() }
        model.projectTargets.first().let{ arg -> runBlocking { model.select(arg) } }
    }

    @Test fun `project tabs expand only their matching palette section`() {
        repository.createSkill(Skill(id = "docs", name = "Palette skill"))
        repository.saveBlock(Block(id = "reviewer", name = "Palette subagent", type = BlockType.SUBAGENT))
        repository.saveBlock(Block(
            id = "tools", name = "Palette MCP", type = BlockType.MCP,
            content = McpConfigCodec.serialize(McpServerConfig.Stdio(command = "tools")),
        ))
        repository.saveGroup(Group(id = "bundle", name = "Palette group", blockIds = listOf(block.id)))
        repository.saveProfile(Profile(id = "review", name = "Palette profile", blockIds = listOf(block.id)))
        val model = model(mcp = true, skills = true, subagents = true)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            RuleblendTheme(theme) {
                Box(Modifier.width(1440.dp).height(900.dp)) { PlaceScreen(model) }
            }
        }
        val rows = mapOf(
            "RULE" to block.name, "SKILL" to "Palette skill", "SUBAGENT" to "Palette subagent",
            "MCP" to "Palette MCP", "GROUP" to "Palette group", "PROFILE" to "Palette profile",
        )
        fun assertSections(active: String) {
            rows.forEach { (kind, name) ->
                compose.onNodeWithTag("place-palette-list").performScrollToKey("type:$kind")
                if (kind == active) compose.onNodeWithText(name).assertIsDisplayed()
                else compose.onNodeWithText(name).assertDoesNotExist()
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            listOf("rules" to "RULE", "skills" to "SKILL", "subagents" to "SUBAGENT", "mcp" to "MCP",
                "rules" to "RULE").forEach { (tab, kind) ->
                compose.onNodeWithTag("place-tab-$tab").performClick()
                assertSections(kind)
            }
            compose.onNodeWithTag("place-palette-list").performScrollToKey("type:GROUP")
            compose.onNodeWithText(strings.libGroups.uppercase()).performClick()
            compose.onNodeWithText("Palette group").assertIsDisplayed()
            compose.onNodeWithTag("place-tab-skills").performClick()
            assertSections("SKILL")
        }
    }

    private fun show(model: IntegrationModel, theme: ThemeMode, blocksExpanded: Boolean = true) {
        compose.setContent {
            RuleblendTheme(theme) {
                PlaceFileColumn(
                    model,
                    rulesExpandedByDefault = blocksExpanded,
                    skillsExpandedByDefault = blocksExpanded,
                    subagentsExpandedByDefault = blocksExpanded,
                    mcpExpandedByDefault = blocksExpanded,
                )
            }
        }
    }

    private fun inBothThemes(model: IntegrationModel, assertions: () -> Unit) {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            RuleblendTheme(theme) {
                PlaceFileColumn(
                    model,
                    rulesExpandedByDefault = true,
                    skillsExpandedByDefault = true,
                    subagentsExpandedByDefault = true,
                    mcpExpandedByDefault = true,
                )
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            assertions()
        }
    }

    @Test fun `a place with nothing installed says so once, and where it would be written`() {
        inBothThemes(model()) {
            compose.onNodeWithText(strings.libRulesLabel).assertIsDisplayed()
            compose.onNodeWithText(strings.placeNoBlocks).assertIsDisplayed()
            // Both addresses in one line rather than an empty block each: the file names are still
            // there for whoever needs them, and they cost one line instead of a screenful.
            compose.onNodeWithText(strings.placeTabWrittenTo("AGENTS.md, CLAUDE.md")).assertIsDisplayed()
        }
    }

    @Test fun `the file view names the place it is showing, above the tabs`() {
        show(model(), ThemeMode.LIGHT)

        // The list on the left can be filtered down to other places; the centre says which place is
        // being read, and the path says which one of two projects of the same name it is.
        val name = compose.onNodeWithText("ledger-kmp").getUnclippedBoundsInRoot()
        val tab = compose.onNodeWithText(strings.libRulesLabel).getUnclippedBoundsInRoot()
        assertTrue(name.bottom <= tab.top, "the place name ends at ${name.bottom}, below a tab strip at ${tab.top}")
        compose.onNodeWithText(project.abbreviateHome()).assertIsDisplayed()
    }

    @Test fun `an empty tab keeps its answer clear of the tab strip`() {
        show(model(), ThemeMode.LIGHT)

        val tab = compose.onNodeWithText(strings.libRulesLabel).getUnclippedBoundsInRoot()
        val note = compose.onNodeWithText(strings.placeNoBlocks).getUnclippedBoundsInRoot()
        assertTrue(
            note.top - tab.bottom >= 10.dp,
            "the empty note starts ${note.top - tab.bottom} under a tab strip that ends at ${tab.bottom}",
        )
    }

    @Test fun `tabs are kinds of object and count what the place holds`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        show(model(), ThemeMode.LIGHT)

        // One rule installed, in one of the two rules files: the tab counts objects, not addresses.
        compose.onNodeWithText(strings.libRulesLabel).assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        val tab = compose.onNodeWithTag("place-tab-rules").fetchSemanticsNode().config
        assertEquals(Role.Tab, tab[SemanticsProperties.Role])
        assertTrue(tab[SemanticsProperties.Selected])
        assertTrue(tab[SemanticsProperties.ContentDescription].joinToString().contains(strings.libRulesLabel))
        assertTrue(tab[SemanticsProperties.ContentDescription].joinToString().contains("1"))
        assertTrue(tab[SemanticsProperties.ContentDescription].joinToString().contains(strings.libStatusSynced))
        // A file name is not a tab any more.
        compose.onAllNodesWithText("AGENTS.md").filterToOne(hasClickAction()).assertDoesNotExist()
    }

    @Test fun `every file of a kind is read in one scroll, each under its own name`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        show(model(), ThemeMode.LIGHT)

        // The rules file and the pointer that leads to it used to be two tabs; they are two sections
        // of one now, so what a place holds is read without clicking through it.
        compose.onNodeWithText("AGENTS.md").assertIsDisplayed()
        compose.onNodeWithText("CLAUDE.md").assertIsDisplayed()
        compose.onNodeWithText(strings.placeModePartial).assertIsDisplayed()
        compose.onNodeWithText("@AGENTS.md").assertIsDisplayed()
    }

    @Test fun `the preview shows a translation beside the text it belongs to`() {
        project.resolve("AGENTS.md").writeText("Keep the ledger tidy.\n")
        val model = model()
        val translation = TranslationModel(
            UppercasingTranslator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlaceFileColumn(model, rulesExpandedByDefault = true, translation = translation)
            }
        }

        // One switch per file now, so the first one is the file the text was written into.
        compose.onAllNodesWithContentDescription(strings.translateShow).onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Keep the ledger tidy.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("KEEP THE LEDGER TIDY.", substring = true).assertIsDisplayed()
    }

    @Test fun `each paragraph is shown against its own translation, under a header per column`() {
        project.resolve("AGENTS.md").writeText("# Ledger\n\nKeep it tidy.\n\nClose it monthly.\n")
        val model = model()
        val translation = TranslationModel(
            UppercasingTranslator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlaceFileColumn(model, rulesExpandedByDefault = true, translation = translation)
            }
        }

        // One switch per file now, so the first one is the file the text was written into.
        compose.onAllNodesWithContentDescription(strings.translateShow).onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.translateOriginalTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.translateColumnTitle("EN", "RU")).assertIsDisplayed()
        // Exact matches: a paragraph that was not cut out on its own would only match as substring.
        compose.onNodeWithText("Keep it tidy.").assertIsDisplayed()
        compose.onNodeWithText("KEEP IT TIDY.").assertIsDisplayed()
        compose.onNodeWithText("Close it monthly.").assertIsDisplayed()
        compose.onNodeWithText("CLOSE IT MONTHLY.").assertIsDisplayed()
    }

    @Test fun `the translation reaches the text a block installed, not only hand-written lines`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        val model = model()
        val translation = TranslationModel(
            UppercasingTranslator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlaceFileColumn(model, rulesExpandedByDefault = true, translation = translation)
            }
        }

        // One switch per file now, so the first one is the file the text was written into.
        compose.onAllNodesWithContentDescription(strings.translateShow).onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithText(block.content, substring = true).assertIsDisplayed()
        compose.onNodeWithText(block.content.uppercase(), substring = true).assertIsDisplayed()
    }

    /** Uppercases prose, so a translation is recognisable without a system helper. */
    private object UppercasingTranslator : Translator {
        override val available = true

        override suspend fun status(from: String, to: String, quality: TranslationQuality) =
            TranslationStatus.INSTALLED

        override suspend fun languages(quality: TranslationQuality) = listOf("en", "ru")

        override suspend fun translate(
            texts: List<String>,
            from: String,
            to: String,
            quality: TranslationQuality,
        ): List<String> = texts.map { it.uppercase() }
    }

    @Test fun `an MCP config and a skills directory are their own tabs, each answering once`() {
        project.resolve(".mcp.json").writeText("{}\n")
        project.resolve(".claude").resolve("skills").createDirectories()
        show(model(mcp = true, skills = true), ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.placeNoServers).assertIsDisplayed()
        compose.onNodeWithText(strings.placeTabWrittenTo(".mcp.json")).assertIsDisplayed()

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.placeNoSkills).assertIsDisplayed()
        compose.onNodeWithText(strings.placeTabWrittenTo(Path.of(".claude/skills").toString() + java.io.File.separator)).assertIsDisplayed()
    }

    @Test fun `skill roots with equal labels use separate lazy list keys`() {
        val native = project.resolve(".codex/skills").also { it.createDirectories() }
        val shared = project.resolve(".agents/skills").also { it.createDirectories() }
        val model = model(skillRoots = listOf(native, shared))
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithText(strings.placeNoSkills).assertIsDisplayed()
    }

    @Test fun `foreign skills show their disk details and stay expandable after ownership`() {
        val foreignSkill = project.resolve(".claude").resolve("skills").resolve("community").also { it.createDirectories() }
        foreignSkill.resolve("SKILL.md").writeText(
            """
            ---
            name: Community guidance
            description: Guidance installed outside Ruleblend.
            version: 1.2.3
            ---

            Read the community details.
            """.trimIndent(),
        )

        val model = model(skills = true)
        show(model, ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithText(strings.placeObjectCount(0, 1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeNoOurSkills(1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeForeignEntries).assertIsDisplayed()
        compose.onNodeWithText("Community guidance").assertIsDisplayed()
        compose.onNodeWithText(foreignSkill.abbreviateHome()).assertIsDisplayed()
        // The description is a hover, not a line of its own: it is the first thing in the text below.
        compose.onAllNodesWithText("Guidance installed outside Ruleblend.").assertCountEquals(0)
        compose.onAllNodesWithText("Read the community details.", substring = true).assertCountEquals(0)

        compose.onNodeWithText("Community guidance").performClick()

        compose.onNodeWithText("Read the community details.", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("place-foreign-save:skill:${foreignSkill.normalize()}").performClick()
        compose.waitUntil(5_000) { model.skills.any { it.id == "community" } }
        val saved = model.skills.single { it.id == "community" }
        compose.waitUntil(5_000) { model.skillEntries.singleOrNull()?.origin == PlaceEntryOrigin.MANAGED }

        // Claiming the directory moves it into the managed section, where it must keep the native
        // SKILL.md text as its unfoldable preview.
        compose.onNodeWithText("Community guidance").performClick()
        compose.onNodeWithText("Read the community details.", substring = true).assertIsDisplayed()
    }

    @Test fun `bundled skill and MCP registration are marked managed and stay read only`() {
        val skill = project.resolve(".claude/skills/ruleblend").also { it.createDirectories() }
        skill.resolve("SKILL.md").writeText(BundledSkill.text)
        val config = project.resolve(".mcp.json")
        val registration = McpServerConfig.Stdio(
            command = root.resolve("Ruleblend").toString(),
            args = listOf("--mcp"),
            env = mapOf(MCP_ENTRY_ENV_KEY to "1"),
        )
        config.writeText(
            McpServersJson.upsert("{}", MCP_SERVER_NAME, McpServersJson.renderEntry(registration)),
        )

        show(model(mcp = true, skills = true, mcpEntry = registration), ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onAllNodesWithText(strings.placeObjectCount(1, 0)).assertCountEquals(2)
        compose.onNodeWithText(strings.placeBuiltInEntries).assertIsDisplayed()
        compose.onNodeWithText(strings.placeBuiltInManaged).assertIsDisplayed()
        compose.onNodeWithText(strings.placeBuiltInReadOnly).assertIsDisplayed()
        compose.onNodeWithTag("place-builtin:skill:${skill.normalize()}").assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-save:skill:${skill.normalize()}").assertDoesNotExist()
        compose.onNodeWithTag("place-foreign-hidden:skill:${skill.normalize()}").assertDoesNotExist()

        compose.onNodeWithText(BundledSkill.NAME).performClick()
        compose.onNodeWithText(BundledSkill.MARKER_PREFIX, substring = true).assertIsDisplayed()

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.onAllNodesWithText(strings.placeObjectCount(1, 0)).assertCountEquals(2)
        compose.onNodeWithText(strings.placeBuiltInEntries).assertIsDisplayed()
        compose.onNodeWithTag("place-builtin:mcp:${config.normalize()}:$MCP_SERVER_NAME").assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-save:mcp:${config.normalize()}:$MCP_SERVER_NAME").assertDoesNotExist()
        compose.onNodeWithTag("place-foreign-remove:mcp:${config.normalize()}:$MCP_SERVER_NAME").assertDoesNotExist()
        compose.onNodeWithTag("place-foreign-hidden:mcp:${config.normalize()}:$MCP_SERVER_NAME").assertDoesNotExist()
    }

    @Test fun `tab header opens and closes every entry at once`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )

        show(model(), ThemeMode.LIGHT, blocksExpanded = false)
        compose.onNodeWithText(block.name).assertIsDisplayed()
        compose.onNodeWithText(block.content).assertDoesNotExist()

        compose.onAllNodesWithTag("place-expand-all").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(block.content).assertIsDisplayed()

        compose.onAllNodesWithTag("place-collapse-all").onFirst().performClick()
        compose.onNodeWithText(block.content).assertDoesNotExist()
    }

    @Test fun `skills subagents and MCP header fold controls align to the right`() {
        val skills = project.resolve(".claude/skills").also { it.createDirectories() }
        val agents = project.resolve(".claude/agents").also { it.createDirectories() }
        skills.resolve("community").also { it.createDirectories() }.resolve("SKILL.md").writeText("# Community\n")
        agents.resolve("reviewer.md").writeText("---\nname: Reviewer\n---\nReview changes.\n")
        val mcp = project.resolve(".mcp.json").also {
            it.writeText("""{"mcpServers":{"community":{"command":"community-server"}}}""")
        }
        show(model(mcp = true, skills = true, subagents = true), ThemeMode.LIGHT)

        listOf(strings.libSkills to skills, strings.libSubagents to agents, strings.libMcp to mcp).forEach { (tab, path) ->
            compose.onNodeWithText(tab).performClick()
            val controls = compose.onNodeWithTag("place-file-controls:$path").getUnclippedBoundsInRoot()
            val collapse = compose.onNodeWithTag("place-collapse-all").getUnclippedBoundsInRoot()
            assertEquals(controls.right, collapse.right, "$tab fold controls must end at the header's right edge")
        }
    }

    @Test fun `skills and subagents edit their own files instead of their folder headers`() {
        val skill = project.resolve(".claude/skills/community").also { it.createDirectories() }
        skill.resolve("SKILL.md").writeText("# Community\n")
        val subagent = project.resolve(".claude/agents/reviewer.md").also { it.parent.createDirectories() }
        subagent.writeText("---\nname: Reviewer\n---\n")
        val model = model(skills = true, subagents = true)
        var edited: Path? = null
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { PlaceFileColumn(model, onEditFile = { edited = it }) }
        }

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onAllNodesWithContentDescription(strings.intEditFile).assertCountEquals(1)
        compose.onNodeWithContentDescription(strings.intEditFile).performClick()
        assertEquals(skill.resolve("SKILL.md"), edited)

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onAllNodesWithContentDescription(strings.intEditFile).assertCountEquals(1)
        compose.onNodeWithContentDescription(strings.intEditFile).performClick()
        assertEquals(subagent, edited)
    }

    @Test fun `a managed skill opens its library editor instead of its installed file`() {
        val skill = repository.createSkill(
            Skill("community", "Community guidance", version = "1", content = "---\nname: community\n---\n# Community\n"),
        )
        val target = ProjectTarget(project, listOf(agent(root, skills = true)))
        SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }).install(target, skill)
        val model = model(skills = true)
        var edited: LibraryObjectKey? = null
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlaceFileColumn(model, onEditBlock = { edited = it }, onEditFile = { error("must not edit managed skill file") })
            }
        }

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithContentDescription(strings.placeEditInLibrary).performClick()

        assertEquals(LibraryObjectKey(LibraryObjectKind.SKILL, skill.id), edited)
        compose.onAllNodesWithContentDescription(strings.intEditFile).assertCountEquals(0)
    }

    @Test fun `deleting a foreign skill requires confirmation and cancellation leaves it intact`() {
        val foreignSkill = project.resolve(".claude/skills/community").also { it.createDirectories() }
        foreignSkill.resolve("SKILL.md").writeText("# Community\n")
        val model = model(skills = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithTag("place-foreign-remove:skill:${foreignSkill.normalize()}").performClick()
        compose.onNodeWithText(strings.placeRemoveForeignConfirm("community", foreignSkill.abbreviateHome())).assertIsDisplayed()
        compose.onAllNodesWithText(strings.actionCancel).onLast().performClick()
        compose.waitForIdle()

        assertTrue(foreignSkill.exists())
        compose.onNodeWithTag("place-foreign-remove:skill:${foreignSkill.normalize()}").performClick()
        compose.onAllNodesWithText(strings.placeRemove).onLast().performClick()
        compose.waitUntil(5_000) { !foreignSkill.exists() }
    }

    @Test fun `an orphaned skill restores its previous library id`() {
        val skill = repository.createSkill(
            dev.ruleblend.core.model.Skill("saved-review", "Saved review", version = "1", content = "# Review\n"),
        )
        val target = ProjectTarget(project, listOf(agent(root, skills = true)))
        SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }).install(target, skill)
        repository.deleteSkill(skill.id)
        val orphan = project.resolve(".claude/skills/saved-review")
        val model = model(skills = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithTag("place-orphan-restore:skill:${orphan.normalize()}").performClick()
        compose.waitUntil(5_000) { model.skillEntries.singleOrNull()?.origin == PlaceEntryOrigin.MANAGED }

        assertEquals(skill.id, repository.loadSkill(skill.id)?.id)
        assertEquals(PlaceEntryOrigin.MANAGED, model.skillEntries.single().origin)
    }

    @Test fun `removing an orphaned skill leaves foreign directories alone`() {
        val skill = repository.createSkill(
            dev.ruleblend.core.model.Skill("saved-review", "Saved review", version = "1", content = "# Review\n"),
        )
        val target = ProjectTarget(project, listOf(agent(root, skills = true)))
        SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }).install(target, skill)
        repository.deleteSkill(skill.id)
        val orphan = project.resolve(".claude/skills/saved-review")
        val foreign = project.resolve(".claude/skills/foreign").also { it.createDirectories() }
        foreign.resolve("SKILL.md").writeText("# Foreign\n")
        val model = model(skills = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithTag("place-orphan-remove:skill:${orphan.normalize()}").performClick()
        compose.onAllNodesWithText(strings.placeRemoveOrphan).onLast().performClick()
        compose.waitUntil(5_000) { !orphan.exists() }

        assertTrue(foreign.exists())
        assertEquals(PlaceEntryOrigin.FOREIGN, model.skillEntries.single { it.value.path == foreign }.origin)
    }

    @Test fun `an MCP entry shared by agents is shown once by its config address`() {
        val config = project.resolve(".mcp.json")
        config.writeText(
            """{"mcpServers":{"context7":{"type":"stdio","command":"npx","args":["-y","ctx7"]}}}""",
        )
        val model = model(
            mcpEntries = mapOf(
                "claude-code" to McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7")),
                "codex" to McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7")),
            ),
        )
        assertEquals(1, model.mcpEntries.size)
        show(model, ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.onNodeWithText(strings.placeObjectCount(0, 1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeNoOurServers(1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeForeignEntries).assertIsDisplayed()
        compose.onAllNodesWithText("context7").assertCountEquals(1)
        compose.onNodeWithText(config.abbreviateHome()).assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-save:mcp:${config.normalize()}:context7").assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-hidden:mcp:${config.normalize()}:context7").assertIsDisplayed()

        compose.onNodeWithText("context7").performClick()

        compose.onNodeWithText("\"command\": \"npx\"", substring = true).assertIsDisplayed()
    }

    @Test fun `a foreign MCP server is saved and taken under management without rewriting its config`() {
        val config = project.resolve(".mcp.json")
        val entry = McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7"))
        val text = """{"mcpServers":{"context7":{"type":"stdio","command":"npx","args":["-y","ctx7"]}}}"""
        config.writeText(text)
        val model = model(mcp = true, mcpEntry = entry)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.onNodeWithTag("place-foreign-save:mcp:${config.normalize()}:context7").performClick()
        compose.waitUntil(5_000) { model.blocks.any { it.id == "context7" && it.type == BlockType.MCP } }
        val saved = model.blocks.single { it.id == "context7" }
        assertEquals(McpConfigCodec.serialize(entry), saved.content)
        compose.waitUntil(5_000) { model.mcpEntries.singleOrNull()?.origin == PlaceEntryOrigin.MANAGED }

        assertEquals(text, config.readText())
        assertEquals(PlaceEntryOrigin.MANAGED, model.mcpEntries.single().origin)
    }

    @Test fun `a foreign MCP server whose id is taken is offered a free id`() {
        repository.saveBlock(Block(id = "context7", name = "Existing rule", type = BlockType.RULE))
        val config = project.resolve(".mcp.json")
        config.writeText("""{"mcpServers":{"context7":{"type":"stdio","command":"npx"}}}""")
        val model = model(mcp = true, mcpEntry = McpServerConfig.Stdio(command = "npx"))
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.onNodeWithTag("place-foreign-save:mcp:${config.normalize()}:context7").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeMcpNameTaken("context7")).assertIsDisplayed()
        compose.onNodeWithTag("place-mcp-name").assertTextEquals("context7-2")
        compose.onNodeWithText(strings.actionCancel).performClick()
    }

    @Test fun `a foreign MCP entry is hidden by its own address and brought back from its own tab`() {
        val config = project.resolve(".mcp.json")
        config.writeText(
            """{"mcpServers":{"context7":{"type":"stdio","command":"npx"},"other":{"type":"stdio","command":"node"}}}""",
        )
        val model = model(mcp = true, mcpEntry = McpServerConfig.Stdio(command = "npx"))
        show(model, ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.onNodeWithText(strings.placeObjectCount(0, 2)).assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-hidden:mcp:${config.normalize()}:context7").performClick()
        compose.waitForIdle()

        // One entry of the file goes, its neighbour stays: the key is the address, not the file.
        compose.onNodeWithText(strings.placeObjectCount(0, 1)).assertIsDisplayed()
        compose.onAllNodesWithText("context7").assertCountEquals(0)
        compose.onNodeWithText("other").assertIsDisplayed()
        assertEquals(
            listOf("mcp:${config.normalize()}:context7"),
            configStore.load().ignoredEntries["project:${project.projectKey()}"],
        )

        compose.onNodeWithText(strings.placeShowHiddenEntries(1)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("place-foreign-hidden:mcp:${config.normalize()}:context7").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeObjectCount(0, 2)).assertIsDisplayed()
        assertEquals(emptyList<String>(), configStore.load().ignoredEntries["project:${project.projectKey()}"].orEmpty())
    }

    @Test fun `an unsupported foreign MCP entry cannot be saved`() {
        val config = project.resolve(".mcp.json")
        config.writeText("""{"mcpServers":{"unsupported":{"type":"ws","url":"wss://mcp.example.com"}}}""")
        val model = model(mcp = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.onNodeWithTag("place-foreign-save:mcp:${config.normalize()}:unsupported").assertDoesNotExist()
    }

    @Test fun `subagent tab is offered only to supported agents and manages parsed foreign definitions`() {
        show(model(), ThemeMode.LIGHT)
        compose.onAllNodesWithText(strings.libSubagents).assertCountEquals(0)

        val foreignSubagent = project.resolve(".claude").resolve("agents").resolve("reviewer.md").also { it.parent.createDirectories() }
        foreignSubagent.writeText(
            """
            ---
            name: Review assistant
            description: Reviews code outside Ruleblend.
            ---

            Inspect the change carefully.
            """.trimIndent(),
        )

        show(model(subagents = true), ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithText(strings.placeObjectCount(0, 1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeNoOurSubagents(1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeForeignEntries).assertIsDisplayed()
        compose.onNodeWithText("Review assistant").assertIsDisplayed()
        compose.onNodeWithText(foreignSubagent.abbreviateHome()).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.actionAdopt).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.placeHideEntry).assertIsDisplayed()

        compose.onNodeWithText("Review assistant").performClick()

        compose.onNodeWithText("Inspect the change carefully.", substring = true).assertIsDisplayed()
    }

    @Test fun `an unparsed foreign subagent cannot be assigned`() {
        val foreignSubagent = project.resolve(".claude/agents/raw.md").also { it.parent.createDirectories() }
        foreignSubagent.writeText("# Hand-written agent\n")
        val model = model(subagents = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSubagents).performClick()

        compose.onNodeWithTag("place-foreign-save:subagent:${foreignSubagent.normalize()}").assertDoesNotExist()
        compose.onNodeWithTag("place-foreign-hidden:subagent:${foreignSubagent.normalize()}").assertIsDisplayed()
    }

    @Test fun `a foreign subagent whose id is taken is offered a free id`() {
        repository.saveBlock(Block(id = "reviewer", name = "Existing reviewer", type = BlockType.RULE))
        val foreignSubagent = project.resolve(".claude/agents/reviewer.md").also { it.parent.createDirectories() }
        foreignSubagent.writeText("---\nname: Review assistant\n---\n\nInspect the change carefully.\n")
        val model = model(subagents = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithTag("place-foreign-save:subagent:${foreignSubagent.normalize()}").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeSubagentNameTaken("reviewer")).assertIsDisplayed()
        compose.onNodeWithTag("place-subagent-name").assertTextEquals("reviewer-2")
        compose.onNodeWithText(strings.actionCancel).performClick()
    }

    @Test fun `taking a foreign subagent records ownership without rewriting its file`() {
        val foreignSubagent = project.resolve(".claude/agents/reviewer.md").also { it.parent.createDirectories() }
        val text = "---\nname: Review assistant\n---\n\nInspect the change carefully.\n"
        foreignSubagent.writeText(text)
        val model = model(subagents = true)
        show(model, ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithTag("place-foreign-save:subagent:${foreignSubagent.normalize()}").performClick()
        compose.waitUntil(5_000) { model.blocks.any { it.id == "reviewer" } }
        val saved = model.blocks.single { it.id == "reviewer" }
        compose.waitUntil(5_000) { model.subagentEntries.singleOrNull()?.origin == PlaceEntryOrigin.MANAGED }

        assertEquals(text, foreignSubagent.readText())
        assertEquals(PlaceEntryOrigin.MANAGED, model.subagentEntries.single().origin)
        // Saving and taking are one step: the saved block never sits unowned beside its source.
        assertEquals(false, model.subagentStatuses.getValue("reviewer").conflict)
        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithText(saved.name).performClick()
        compose.onNodeWithText("Inspect the change carefully.", substring = true).assertIsDisplayed()
    }

    @Test fun `a managed subagent is released to a foreign entry and taken back under the same block`() {
        val foreignSubagent = project.resolve(".claude/agents/reviewer.md").also { it.parent.createDirectories() }
        val text = "---\nname: Review assistant\n---\n\nInspect the change carefully.\n"
        foreignSubagent.writeText(text)
        val model = model(subagents = true)
        show(model, ThemeMode.LIGHT, blocksExpanded = false)
        val key = "subagent:${foreignSubagent.normalize()}"

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithTag("place-foreign-save:$key").performClick()
        compose.waitUntil(5_000) { model.subagentEntries.singleOrNull()?.origin == PlaceEntryOrigin.MANAGED }

        compose.onNodeWithContentDescription(strings.placeUnlinkEntry).performClick()
        compose.waitUntil(5_000) { model.subagentEntries.singleOrNull()?.origin == PlaceEntryOrigin.FOREIGN }

        // The file stays as it was, the library keeps the block, and the address shows once, as foreign.
        assertEquals(text, foreignSubagent.readText())
        assertEquals(listOf("reviewer"), model.blocks.filter { it.type == BlockType.SUBAGENT }.map { it.id })
        compose.onAllNodesWithText(strings.libStatusModified).assertCountEquals(0)
        compose.onNodeWithTag("place-foreign-take:$key").performClick()
        compose.waitUntil(5_000) { model.subagentEntries.singleOrNull()?.origin == PlaceEntryOrigin.MANAGED }

        assertEquals(text, foreignSubagent.readText())
        assertEquals(listOf("reviewer"), model.blocks.filter { it.type == BlockType.SUBAGENT }.map { it.id })
    }

    @Test fun `a foreign subagent stays hidden after reappearing and can be restored`() {
        val foreignSubagent = project.resolve(".claude/agents/reviewer.md")
        fun writeForeignSubagent() {
            foreignSubagent.parent.createDirectories()
            foreignSubagent.writeText("---\nname: Review assistant\n---\n\nInspect the change carefully.\n")
        }
        writeForeignSubagent()
        val model = model(subagents = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithContentDescription(strings.placeHideEntry).performClick()
        compose.waitUntil {
            compose.onAllNodesWithText(strings.placeShowHiddenEntries(1)).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        assertEquals(
            listOf("subagent:${foreignSubagent.normalize()}"),
            configStore.load().ignoredEntries["project:${project.projectKey()}"],
        )

        foreignSubagent.toFile().delete()
        writeForeignSubagent()
        runBlocking { model.select(model.projectTargets.single()) }
        compose.waitForIdle()

        compose.onAllNodesWithText("Review assistant").assertCountEquals(0)
        compose.onNodeWithText(strings.placeShowHiddenEntries(1)).performClick()
        compose.onNodeWithText("Review assistant").assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.placeUnhideEntry).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), configStore.load().ignoredEntries["project:${project.projectKey()}"].orEmpty())
    }

    @Test fun `a foreign skill named like a library skill is linked instead of saved`() {
        repository.createSkill(
            dev.ruleblend.core.model.Skill("community", "Community", version = "1", content = "---\nname: community\n---\n"),
        )
        val foreignSkill = project.resolve(".claude").resolve("skills").resolve("community").also { it.createDirectories() }
        foreignSkill.resolve("SKILL.md").writeText("---\nname: Community guidance\n---\n")
        val model = model(skills = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSkills).performClick()
        // Saving would only shelve a second copy of the library skill, so only linking is offered.
        compose.onNodeWithTag("place-foreign-take:skill:${foreignSkill.normalize()}").assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-save:skill:${foreignSkill.normalize()}").assertDoesNotExist()
        compose.onNodeWithTag("place-foreign-hidden:skill:${foreignSkill.normalize()}").assertIsDisplayed()
    }

    @Test fun `a foreign subagent named like a library subagent is linked instead of saved`() {
        repository.saveBlock(Block(id = "reviewer", name = "Existing reviewer", type = BlockType.SUBAGENT))
        val foreignSubagent = project.resolve(".claude/agents/reviewer.md").also { it.parent.createDirectories() }
        foreignSubagent.writeText("---\nname: Review assistant\n---\n\nInspect the change carefully.\n")
        show(model(subagents = true), ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.onNodeWithTag("place-foreign-take:subagent:${foreignSubagent.normalize()}").assertIsDisplayed()
        compose.onNodeWithTag("place-foreign-save:subagent:${foreignSubagent.normalize()}").assertDoesNotExist()
    }

    @Test fun `a foreign skill stays hidden after reappearing and can be restored`() {
        val foreignSkill = project.resolve(".claude").resolve("skills").resolve("community")
        fun writeForeignSkill() {
            foreignSkill.createDirectories()
            foreignSkill.resolve("SKILL.md").writeText("---\nname: Community guidance\n---\n")
        }
        writeForeignSkill()
        val model = model(skills = true)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithText(strings.placeObjectCount(0, 1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeForeignEntries).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.placeHideEntry).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeObjectCount(0, 0)).assertIsDisplayed()
        compose.onAllNodesWithText(strings.placeForeignEntries).assertCountEquals(0)
        compose.onAllNodesWithText("Community guidance").assertCountEquals(0)
        compose.onNodeWithText(strings.placeShowHiddenEntries(1)).assertIsDisplayed()
        assertEquals(
            listOf("skill:${foreignSkill.normalize()}"),
            configStore.load().ignoredEntries["project:${project.projectKey()}"],
        )

        foreignSkill.toFile().deleteRecursively()
        writeForeignSkill()
        runBlocking { model.select(model.projectTargets.single()) }
        compose.waitForIdle()

        compose.onAllNodesWithText("Community guidance").assertCountEquals(0)
        compose.onNodeWithText(strings.placeShowHiddenEntries(1)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Community guidance").assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.placeUnhideEntry).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeObjectCount(0, 1)).assertIsDisplayed()
        compose.onNodeWithText(strings.placeForeignEntries).assertIsDisplayed()
        assertEquals(emptyList<String>(), configStore.load().ignoredEntries["project:${project.projectKey()}"].orEmpty())
    }

    @Test fun `the show-hidden switch belongs to the tab whose entries are hidden`() {
        val foreignSkill = project.resolve(".claude").resolve("skills").resolve("community")
        foreignSkill.createDirectories()
        foreignSkill.resolve("SKILL.md").writeText("---\nname: Community guidance\n---\n")
        val foreignSubagent = project.resolve(".claude").resolve("agents").resolve("reviewer.md")
        foreignSubagent.parent.createDirectories()
        foreignSubagent.writeText("---\nname: Review assistant\n---\n\nReview the diff.\n")
        show(model(skills = true, subagents = true), ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(strings.libSkills).performClick()
        compose.onNodeWithContentDescription(strings.placeHideEntry).performClick()
        compose.waitUntil {
            compose.onAllNodesWithText(strings.placeShowHiddenEntries(1)).fetchSemanticsNodes().size == 1
        }
        compose.waitForIdle()
        compose.onNodeWithText(strings.placeShowHiddenEntries(1)).assertIsDisplayed()

        // A place counts its hidden entries across every kind; the tab that hides none offers nothing.
        compose.onNodeWithText(strings.libSubagents).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(strings.placeShowHiddenEntries(1)).assertCountEquals(0)
        compose.onNodeWithText("Review assistant").assertIsDisplayed()
    }

    @Test fun `agent copies edited apart are read one per agent, and each is saved by name`() {
        val entry = McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7"))
        val server = repository.saveBlock(
            Block(id = "context7", name = "context7", type = BlockType.MCP, content = McpConfigCodec.serialize(entry)),
        )
        project.resolve(".mcp.json").writeText("{}\n")
        val copies = mapOf(
            "claude-code" to McpServerConfig.Stdio(command = "bunx", args = listOf("-y", "ctx7")),
            "codex" to McpServerConfig.Stdio(command = "/opt/wrapper", args = listOf("-y", "ctx7")),
        )
        // Ruleblend installed the library entry and both agents were edited afterwards: recorded, so
        // the two copies read as one drift rather than as two foreign entries.
        val state = McpStateStore(root)
        copies.keys.forEach { agentId ->
            state.record(McpInstallRecord("project:${project.projectKey()}", agentId, server.id, server.version, entry))
        }
        show(model(mcpEntries = copies), ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeDriftApart).assertIsDisplayed()
        compose.onNodeWithText("Claude Code").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertIsDisplayed()
        // One offer per copy and none for the row: with the copies apart there is no single local edit.
        compose.onAllNodesWithText(strings.intAcceptLocal("2")).assertCountEquals(2)
    }

    @Test fun `a tab carries the status of what it holds, and the section says which file`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        show(model(), ThemeMode.LIGHT)

        // The tab speaks its title and state together; the file section has its own status.
        compose.onAllNodesWithContentDescription(strings.libStatusSynced).assertCountEquals(1)
        val tab = compose.onNodeWithTag("place-tab-rules").fetchSemanticsNode().config
        assertTrue(tab[SemanticsProperties.ContentDescription].joinToString().contains(strings.libStatusSynced))
    }

    @Test fun `a hand-edited block turns the dot of its own tab, not only the one in the list`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        file.writeText(file.readText().replace(block.content, "Edited by hand."))
        show(model(), ThemeMode.DARK)

        // The tab keeps its name while reporting the edit; the file section still reports its status.
        compose.onAllNodesWithContentDescription(strings.libStatusModified).assertCountEquals(1)
        val tab = compose.onNodeWithTag("place-tab-rules").fetchSemanticsNode().config
        assertTrue(tab[SemanticsProperties.ContentDescription].joinToString().contains(strings.libStatusModified))
    }

    @Test fun `legacy markers are previewed as such, with the hand-written text kept around them`() {
        project.resolve("AGENTS.md").writeText(
            LegacyMarkers.upsert("# ledger-kmp\n", regionFor(block)) + "\nBuild with make.\n",
        )

        inBothThemes(model()) {
            compose.onNodeWithText(strings.placeModeLegacy).assertIsDisplayed()
            compose.onNodeWithText("# ledger-kmp").assertIsDisplayed()
            compose.onNodeWithText("Build with make.").assertIsDisplayed()
            compose.onNodeWithText("Kotlin style").assertIsDisplayed()
            compose.onNodeWithText(strings.libStatusSynced).assertIsDisplayed()
        }
    }

    @Test fun `a partial file shows hand-written text muted above its managed run`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )

        inBothThemes(model()) {
            compose.onNodeWithText(strings.placeModePartial).assertIsDisplayed()
            compose.onNodeWithText("# ledger-kmp").assertIsDisplayed()
            compose.onNodeWithText("@AGENTS.md").assertIsDisplayed()
            compose.onNodeWithText("Kotlin style").assertIsDisplayed()
        }
    }

    @Test fun `hand-written text is named, marked as nobody's, and offered to the library on its card`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# Ledger house rules\n", regionFor(block)),
        )

        inBothThemes(model()) {
            // Its own heading is the only name an unadopted fragment has, and the one thing that can
            // be done with it sits on the same line rather than behind a right-click nobody guesses.
            compose.onNodeWithText("Ledger house rules").assertIsDisplayed()
            compose.onNodeWithText(strings.placeNotInLibrary).assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.placeSaveToLibrary).assertIsDisplayed()
        }
    }

    @Test fun `an owned file says the whole file is Ruleblend's`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.OWNED).upsert("", regionFor(block)),
        )

        inBothThemes(model()) {
            compose.onNodeWithText(strings.placeModeOwned).assertIsDisplayed()
            compose.onNodeWithText(strings.placeOwnedNotice).assertIsDisplayed()
            compose.onNodeWithText("Kotlin style").assertIsDisplayed()
        }
    }

    @Test fun `a full Russian file header keeps the owned badge on one line`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.OWNED).upsert("", regionFor(block)))
        BackupService(root.resolve("backups")).backup(file)
        val model = model()
        val translation = TranslationModel(
            UppercasingTranslator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalStrings provides RuStrings) {
                    Box(Modifier.width(880.dp).fillMaxHeight()) {
                        PlaceFileColumn(model, onEditFile = {}, translation = translation)
                    }
                }
            }
        }

        val badge = compose.onNodeWithText(RuStrings.placeModeOwned).getUnclippedBoundsInRoot()
        assertTrue(badge.right - badge.left > 40.dp, "the owned badge was squeezed to ${badge.right - badge.left}")
        assertTrue(badge.bottom - badge.top < 30.dp, "the owned badge wrapped to ${badge.bottom - badge.top}")
    }

    @Test fun `a block row folds the text it installed away and back`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        show(model(), ThemeMode.LIGHT)

        // What is installed here is the answer this column exists for, so it is open on arrival.
        compose.onNodeWithText(block.content).assertIsDisplayed()

        compose.onNodeWithText("Kotlin style").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(block.content).assertDoesNotExist()

        compose.onNodeWithText("Kotlin style").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(block.content).assertIsDisplayed()
    }

    @Test fun `the collapsed setting closes installed bodies on entry, and the header still opens them`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        show(model(), ThemeMode.LIGHT, blocksExpanded = false)

        compose.onNodeWithText(block.content).assertDoesNotExist()

        compose.onNodeWithText("Kotlin style").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(block.content).assertIsDisplayed()
    }

    @Test fun `a hand-edited block offers the three ways out, and keep settles it without a write`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        file.writeText(file.readText().replace(block.content, "Edited by hand."))
        val model = model()

        inBothThemes(model) {
            compose.onNodeWithText(strings.libStatusModified).assertIsDisplayed()
            compose.onNodeWithText(strings.placeDriftNotice).assertIsDisplayed()
            compose.onNodeWithText(strings.intTakeLibrary).assertIsDisplayed()
            compose.onNodeWithText(strings.intAcceptLocal("${block.version + 1}")).assertIsDisplayed()
            compose.onNodeWithText(strings.placeKeep).assertIsDisplayed()
        }

        val before = file.readText()
        compose.onNodeWithText(strings.placeKeep).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeDriftNotice).assertDoesNotExist()
        compose.onNodeWithText(strings.placeDriftKept).assertIsDisplayed()
        compose.onNodeWithText(strings.libStatusModified).assertDoesNotExist()
        assertEquals(before, file.readText())
        // The answer outlives the screen: a fresh model over the same config still holds it.
        assertTrue(model().driftKept(block.id, "Edited by hand."))
    }

    @Test fun `a kept edit is asked about again once the file drifts further`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        file.writeText(file.readText().replace(block.content, "Edited by hand."))
        val model = model()
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.placeKeep).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.placeDriftKept).assertIsDisplayed()

        file.writeText(file.readText().replace("Edited by hand.", "Edited again."))

        assertFalse(model().driftKept(block.id, "Edited again."))
    }

    @Test fun `restoring the library copy drops the kept answer, so the next edit is reported`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        file.writeText(file.readText().replace(block.content, "Edited by hand."))
        val model = model()
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.placeKeep).performClick()
        compose.waitForIdle()
        // A settled row no longer offers the restore, so the write is made the way any other surface
        // makes it — what is under test is that resolving the drift drops the answer about it.
        compose.runOnIdle { runBlocking { model.install(block, force = true) } }
        compose.waitForIdle()

        file.writeText(file.readText().replace(block.content, "Edited by hand."))
        assertFalse(model().driftKept(block.id, "Edited by hand."))
    }

    @Test fun `a synced block offers only the way out, and pressing it clears the region`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        val model = model()
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.intTakeLibrary).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.placeRemove).performClick()
        compose.waitForIdle()

        assertEquals(null, model.statuses[block.id])
        assertFalse(block.content in file.readText())
    }

    @Test fun `a managed rule opens its library editor from the block row`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        val model = model()
        var edited: dev.ruleblend.app.library.LibraryObjectKey? = null
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlaceFileColumn(model, onEditBlock = { edited = it })
            }
        }

        compose.onNodeWithContentDescription(strings.libEditFocus).performClick()

        assertEquals(
            dev.ruleblend.app.library.LibraryObjectKey(dev.ruleblend.app.library.LibraryObjectKind.RULE, block.id),
            edited,
        )
    }

    @Test fun `a drifted body is shown against the library one, so the edit itself is readable`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        file.writeText(file.readText().replace(block.content, "Edited by hand."))

        inBothThemes(model()) {
            compose.onNodeWithText("\u2212 ${block.content}").assertIsDisplayed()
            compose.onNodeWithText("+ Edited by hand.").assertIsDisplayed()
        }
    }

    @Test fun `a narrow column wraps the writes of a drifted row instead of squeezing them`() {
        val file = project.resolve("AGENTS.md")
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)))
        file.writeText(file.readText().replace(block.content, "Edited by hand."))
        val model = model()
        val labels = listOf(
            strings.intTakeLibrary,
            strings.intAcceptLocal("${block.version + 1}"),
            strings.placeKeep,
            strings.placeRemove,
        )
        var width by mutableStateOf(1200.dp)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(width).fillMaxHeight()) { PlaceFileColumn(model) }
            }
        }
        val natural = labels.associateWith { label -> compose.onNode(hasText(label) or hasContentDescription(label)).buttonWidth() }

        // Half the width the four buttons need side by side: they have to move to another line, and
        // a button pressed into a column of single letters is what a plain Row used to do here.
        compose.runOnIdle { width = 360.dp }
        labels.forEach { label ->
            val got = compose.onNode(hasText(label) or hasContentDescription(label)).buttonWidth()
            assertTrue(got == natural[label], "\"$label\" is $got wide in a narrow column, not ${natural[label]}")
        }
    }

    @Test fun `the translation switch sits on the file it acts on, under the tabs and to the right`() {
        project.resolve("AGENTS.md").writeText("Keep the ledger tidy.\n")
        val model = model()
        val translation = TranslationModel(
            UppercasingTranslator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
        )
        val width = 360.dp
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(width).fillMaxHeight()) {
                    PlaceFileColumn(model, translation = translation)
                }
            }
        }

        val button = compose.onNodeWithContentDescription(strings.translateShow).getUnclippedBoundsInRoot()
        assertTrue(button.right <= width, "the translation switch ends at ${button.right} of a $width column")
        // Below the tab strip: the switch answers for one file, not for the kind of object shown.
        val tab = compose.onNodeWithText(strings.libRulesLabel).getUnclippedBoundsInRoot()
        assertTrue(
            button.top >= tab.bottom,
            "the switch starts at ${button.top}, inside a tab strip that ends at ${tab.bottom}",
        )
        // A narrow column keeps the file name readable above the actions.
        val name = compose.onNodeWithText("AGENTS.md").getUnclippedBoundsInRoot()
        assertTrue(
            name.right - name.left >= 60.dp && name.right <= width,
            "the file name occupies ${name.right - name.left} of a $width column",
        )
        assertTrue(
            button.top >= name.bottom,
            "the switch at ${button.top} sits above the file name ending at ${name.bottom}",
        )
    }

    @Test fun `a hand-edited MCP entry is read as a diff, and is not offered a translation`() {
        val server = Block(
            id = "context7",
            name = "Context7",
            type = BlockType.MCP,
            content = McpConfigCodec.serialize(
                McpServerConfig.Stdio(command = "npx", args = listOf("-y", "context7")),
            ),
        )
        repository.saveBlock(server)
        project.resolve(".mcp.json").writeText("{}\n")
        val model = model(
            mcp = true,
            mcpEntry = McpServerConfig.Stdio(command = "npx", args = listOf("-y", "context7@next")),
        )
        val translation = TranslationModel(
            UppercasingTranslator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                PlaceFileColumn(model, mcpExpandedByDefault = true, translation = translation)
            }
        }

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(strings.placeDriftNotice).assertIsDisplayed()
        // The line that differs, from the config file rather than from the library.
        compose.onNodeWithText("context7@next", substring = true).assertIsDisplayed()
        // A server entry is JSON: a second column beside it would say nothing.
        compose.onAllNodesWithContentDescription(strings.translateShow).assertCountEquals(0)
    }

    @Test fun `a hand-edited MCP entry can be saved as the library's next version`() {
        val server = Block(
            id = "context7",
            name = "Context7",
            type = BlockType.MCP,
            content = McpConfigCodec.serialize(
                McpServerConfig.Stdio(command = "npx", args = listOf("-y", "context7")),
            ),
        )
        repository.saveBlock(server)
        project.resolve(".mcp.json").writeText("{}\n")
        val edited = McpServerConfig.Stdio(command = "npx", args = listOf("-y", "context7@next"))
        val model = model(mcp = true, mcpEntry = edited)
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText(strings.libMcp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(strings.intAcceptLocal("${server.version + 1}")).performClick()
        // The write and the refresh behind it are off the UI thread, so the row levelling is what
        // says the whole action landed — not the frame that handled the click.
        compose.waitUntil { model.mcpStatuses[server.id]?.status == InstallStatus.SYNCED }

        // The library now holds the entry the config file holds, one version on, and the row is level.
        val saved = repository.loadBlock(server.id)
        assertEquals(McpConfigCodec.serialize(edited), saved?.content)
        assertEquals(server.version + 1, saved?.version)
        assertEquals(InstallStatus.SYNCED, model.mcpStatuses[server.id]?.status)
    }

    @Test fun `the preview keeps a gap under its last line when it is scrolled to the end`() {
        val long = block.copy(content = (1..12).joinToString("\n") { "Rule line $it." })
        repository.saveBlock(long)
        project.resolve("AGENTS.md").writeText(WrappedRun(TargetOwnershipMode.OWNED).upsert("", regionFor(long)))
        val height = 280.dp  // the project header alone takes about 130 dp
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(600.dp).height(height)) { PlaceFileColumn(model()) }
            }
        }

        // The tab is taller than the column, so this lands on the end of the scroll, not mid-list:
        // the pointer section closes the Rules tab, under the file that holds the blocks.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("footer:CLAUDE.md")
        compose.waitForIdle()
        val last = strings.placePointerNotice("AGENTS.md")
        val bottom = compose.onNodeWithText(last).getUnclippedBoundsInRoot().bottom
        assertTrue(bottom > height - 40.dp, "the tab is not scrolled to its end: the last line sits at $bottom")
        assertTrue(bottom <= height - 12.dp, "the last line ends at $bottom of a $height column, with no gap under it")
    }

    private fun SemanticsNodeInteraction.buttonWidth(): Dp =
        getUnclippedBoundsInRoot().let { it.right - it.left }

    @Test fun `the pointer section shows the import the agent follows, not a block list`() {
        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("# ledger-kmp\n", regionFor(block)),
        )
        val model = model()
        show(model, ThemeMode.LIGHT)

        compose.onNodeWithText("@AGENTS.md").assertIsDisplayed()
        compose.onNodeWithText(strings.placePointerNotice("AGENTS.md")).assertIsDisplayed()
    }

    /**
     * The column is one composable reused for every place, so its scroll offset is its own state
     * unless the place is part of the key. Carrying an offset across a switch opens the next place
     * halfway down a file nobody has read yet, with its header off the top of the column.
     */
    @Test fun `switching place reads the new file from the top, not from the old scroll offset`() {
        val long = block.copy(content = (1..12).joinToString("\n") { "Rule line $it." })
        repository.saveBlock(long)
        val other = root.resolve("transit-ios").also { it.createDirectories() }
        configStore.save(AppConfig(projects = listOf(project.toString(), other.toString())))
        val installed = WrappedRun(TargetOwnershipMode.OWNED).upsert("", regionFor(long))
        project.resolve("AGENTS.md").writeText(installed)
        other.resolve("AGENTS.md").writeText(installed)
        val model = model().also { m -> m.projectTargets.first { it.dir == project }.let { t -> runBlocking { m.select(t) } } }
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(600.dp).height(280.dp)) { PlaceFileColumn(model) }
            }
        }

        // The first row of the tab: the address the file is at, above everything installed in it.
        val header = "AGENTS.md"
        compose.onNodeWithText(header).assertIsDisplayed()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("footer:CLAUDE.md")
        compose.waitForIdle()
        compose.onNodeWithText(header).assertDoesNotExist()

        compose.runOnIdle { model.projectTargets.first { it.dir == other }.let{ arg -> runBlocking { model.select(arg) } } }
        compose.waitForIdle()

        compose.onNodeWithText(header).assertIsDisplayed()
    }
}
