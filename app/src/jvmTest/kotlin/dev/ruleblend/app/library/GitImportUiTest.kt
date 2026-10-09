package dev.ruleblend.app.library

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import dev.ruleblend.app.home.HomeModel
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.SubagentDraft
import dev.ruleblend.core.integration.subagentAgentFields
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.storage.GitImportFileError
import dev.ruleblend.core.storage.GitRepositoryFetcher
import dev.ruleblend.core.storage.GitRepositoryImportPlan
import dev.ruleblend.core.storage.GitSubagentCandidate
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.SourceCheckReport
import dev.ruleblend.core.usecase.SourceCheckRepository
import dev.ruleblend.core.usecase.SourceCheckRepositoryStatus
import dev.ruleblend.core.usecase.SourceCheckSkill
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import dev.ruleblend.core.usecase.SourceCheckSubagent
import dev.ruleblend.core.usecase.SourcesStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

class GitImportUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var config: ConfigStore
    private lateinit var archive: LibraryArchive
    private lateinit var model: LibraryModel
    private lateinit var plan: GitRepositoryImportPlan
    private var fetches = 0
    private val fetcher = GitRepositoryFetcher { fetches++; plan }
    private val url = "https://example.com/definitions.git"
    private val nativePath = ".codex/agents/review.toml"
    private val ambiguousPath = "agents/ambiguous.md"

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("ruleblend-git-import-ui-")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        config = ConfigStore(root.resolve("config.json"))
        archive = LibraryArchive(root.resolve("library"), repository)
        plan = definitions("old", "Old instructions")
        model = LibraryModel(repository, archive, config, fetcher,
            subagentAgents = listOf(CodexAdapter(root.resolve("home"))).subagentAgentFields())
        runBlocking { model.load() }
    }

    @AfterTest fun tearDown() { root.toFile().deleteRecursively() }

    private fun definitions(revision: String, body: String): GitRepositoryImportPlan {
        val skill = Skill("review", "review", "Review", revision, body,
            GitSkillSource(url, revision, "skills/review"))
        val draft = SubagentDraft("review", "Review", mapOf("model" to "native-model"), body)
        return GitRepositoryImportPlan(url, revision, revision,
            listOf(SkillSnapshot(skill, listOf(SkillFile("SKILL.md", body.encodeToByteArray())))),
            listOf(GitSubagentCandidate(nativePath, "codex", mapOf("codex" to draft), "name = 'review'")))
    }

    private fun importBoth() = runBlocking {
        assertTrue(model.applyGitImport(plan, GitImportSelection.initial(plan, model.skills, model.blocks)))
    }

    private fun discover(onDismiss: () -> Unit = {}) {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { ImportSkillsDialog(model, url, onDismiss) }
        }
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("library-git-import-list").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `known definitions start unticked and ambiguous sources require an assistant`() {
        importBoth()
        val ambiguous = GitSubagentCandidate(ambiguousPath, null,
            mapOf("claude-code" to SubagentDraft("Ambiguous", content = "Body"),
                "kimi-code" to SubagentDraft("Ambiguous", content = "Body")), "---\nname: Ambiguous\n---\nBody")
        plan = plan.copy(subagents = plan.subagents + ambiguous)
        val selection = GitImportSelection.initial(plan, model.skills, model.blocks)
        assertTrue(selection.isEmpty)
        assertTrue(selection.toggleSubagent(ambiguousPath, true).isEmpty)
        val chosen = selection.chooseAssistant(ambiguousPath, "kimi-code").toggleSubagent(ambiguousPath, true)
        assertEquals(mapOf(ambiguousPath to "kimi-code"), chosen.selectedSubagents)
        assertTrue(chosen.chooseAssistant(ambiguousPath, "claude-code").isEmpty)
        discover()
        compose.onNodeWithTag("library-git-import:skills/review").assertIsOff()
        compose.onNodeWithTag("library-git-import-subagent:$nativePath").assertIsOff()
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
    }

    @Test fun `dialog previews invalid files and imports the explicit Markdown interpretation`() {
        val source = "---\nname: ambiguous\nmodel: haiku\n---\nReview carefully."
        val candidate = GitSubagentCandidate(ambiguousPath, null, mapOf(
            "claude-code" to SubagentDraft("ambiguous", "Review", mapOf("model" to "haiku"), "Review carefully."),
            "kimi-code" to SubagentDraft("ambiguous", "Review", mapOf("foreign" to "haiku"), "Review carefully.")), source)
        plan = plan.copy(skills = emptyList(), subagents = listOf(candidate),
            errors = listOf(GitImportFileError("agents/bad.toml", "Unsupported array", "tools = ['Read']")))
        var dismissed = false
        discover { dismissed = true }
        compose.onNodeWithTag("library-git-import-subagent:$ambiguousPath").performClick()
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("library-git-assistant:$ambiguousPath:kimi-code").performClick()
        compose.onNodeWithTag("library-git-import-subagent:$ambiguousPath").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("library-git-preview:$ambiguousPath").performClick()
        compose.onNodeWithTag("library-git-preview-text:$ambiguousPath").assertTextEquals(source)
        compose.onNodeWithTag("library-git-import-list")
            .performScrollToNode(hasTestTag("library-git-error:agents/bad.toml"))
        compose.onNodeWithTag("library-git-preview:agents/bad.toml").performClick()
        compose.onNodeWithTag("library-git-preview-text:agents/bad.toml").assertTextEquals("tools = ['Read']")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { dismissed }
        val saved = repository.listBlocks().single()
        assertEquals("kimi-code", saved.source?.assistant)
        assertEquals("haiku", saved.variant("kimi-code")["foreign"])
        assertTrue(saved.variant("claude-code").fields.isEmpty())
        assertTrue(repository.listSkills().isEmpty())
    }

    @Test fun `imported editor protects every field and opens an editable fork`() {
        importBoth()
        model.selectBlock("review")
        val original = assertNotNull(model.draft)
        model.editDraft { it.copy(name = "Changed", content = "Changed") }
        assertEquals(original, model.draft)
        runBlocking {
            assertNull(model.saveDraft())
            assertFalse(model.saveComparedContent(LibraryObjectKey(LibraryObjectKind.SUBAGENT, "review"), "Changed"))
        }
        assertTrue(CompareSubject.from(original).readOnly)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                EditorPane(model, onDeleteBlock = {}, onDeleteGroup = {}, onDeleteProfile = {},
                    onSplitBlock = {}, onCompare = {})
            }
        }
        compose.onNodeWithTag("library-editor-block-name").assertIsNotEnabled()
        compose.onNodeWithTag("library-editor-block-description").assertIsNotEnabled()
        compose.onNodeWithTag(subagentFieldTag("codex", "model")).assertIsNotEnabled()
        compose.onNodeWithTag("library-editor-block-content").assert(hasSetTextAction().not())
        assertEquals(original, model.draft)
        compose.onNodeWithTag("library-editor-save").assertDoesNotExist()
        compose.onNodeWithTag("library-editor-subagent-source").assertExists()
        compose.onNodeWithTag("library-editor-subagent-fork").performClick()
        compose.waitForIdle()
        assertNull(model.draft?.source)
        assertEquals(original.source, model.draft?.forkedFrom)
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Local changes")
        compose.waitForIdle()
        assertEquals("Local changes", model.draft?.content)
        assertEquals(original, repository.loadBlock(original.id))
    }

    @Test fun `mixed source update fetches once and preserves both namespaces and memberships`() = runBlocking {
        assertTrue(model.applyGitImport(plan, GitImportSelection.initial(plan, model.skills, model.blocks)))
        repository.saveGroup(Group("team", "Team", blockIds = listOf("review"), skillIds = listOf("review")))
        model.selectBlock("review")
        model.duplicateBlock(assertNotNull(model.draft))
        val fork = assertNotNull(model.draft)
        plan = definitions("new", "Updated instructions")
        fetches = 0
        val imports = ImportLibrary(repository, archive, config, fetcher)
        val report = SourceCheckReport(
            listOf(SourceCheckRepository(url, "new", SourceCheckRepositoryStatus.CHECKED)),
            listOf(SourceCheckSkill("review", url, "skills/review", SourceCheckSkillStatus.UPDATE_AVAILABLE)),
            listOf(SourceCheckSubagent("review", url, nativePath, "codex", SourceCheckSkillStatus.UPDATE_AVAILABLE)))
        val home = HomeModel(repository, config, agents = emptyList(), usageSource = LibraryUsageSource.None,
            checkImportedSkillSources = { report }, sourceStateStore = SourcesStateStore(root),
            updateImportedDefinitions = { skills, agents -> imports.updateDefinitions(skills, agents) })
        home.refreshSourceUpdates()
        assertTrue(home.sourceUpdates.visible)
        assertEquals(setOf("review"), home.sourceUpdates.sources.single().subagentIds)
        assertEquals(setOf("review"), home.sourceUpdates.sources.single().skillIds)
        home.checkSkillSources()
        assertEquals(setOf("review", "subagent:review"), home.sourceUpdates.updateIds)
        home.updateSourceSkills(home.sourceUpdates.updateIds)
        assertEquals(1, fetches)
        assertEquals("Updated instructions", repository.loadBlock("review")?.content)
        assertEquals("Updated instructions", repository.loadSkill("review")?.content)
        assertEquals(fork, repository.loadBlock(fork.id))
        assertEquals(listOf("review"), repository.loadGroup("team")?.blockIds)
        assertEquals(listOf("review"), repository.loadGroup("team")?.skillIds)
        assertTrue(home.sourceUpdates.updateIds.isEmpty())
        assertNull(home.sourceUpdates.failure)
        home.refreshSourceUpdates()
        assertTrue(home.sourceUpdates.updateIds.isEmpty())
    }

    @Test fun `editor update replaces the imported definition and keeps its native fields`() {
        importBoth()
        model.selectBlock("review")
        plan = definitions("new", "Updated instructions")
        fetches = 0
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                EditorPane(model, onDeleteBlock = {}, onDeleteGroup = {}, onDeleteProfile = {},
                    onSplitBlock = {}, onCompare = {})
            }
        }
        compose.onNodeWithTag("library-editor-subagent-update").performClick()
        compose.waitUntil(5_000) { model.draft?.source?.revision == "new" }
        compose.onNodeWithTag("library-editor-block-content").assertTextEquals("Updated instructions")
        assertEquals(1, fetches)
        assertEquals("review", model.draft?.id)
        assertEquals(2, model.draft?.version)
        assertEquals("native-model", model.draft?.variant("codex")?.get("model"))
        assertNull(model.failure)
    }

    @Test fun `subagent-only source remains visible and catalog filters carry its provenance`() = runBlocking {
        plan = plan.copy(skills = emptyList())
        importBoth()
        val home = HomeModel(repository, config, agents = emptyList(), usageSource = LibraryUsageSource.None)
        home.refreshSourceUpdates()
        assertTrue(home.sourceUpdates.visible)
        assertEquals(setOf("review"), home.sourceUpdates.sources.single().subagentIds)
        val item = model.catalog.objects.single { it.key.kind == LibraryObjectKind.SUBAGENT }
        assertTrue(item.importedFromGit)
        assertTrue(CompareSubject.from(item).readOnly)
        assertEquals(listOf(item), model.catalog.filtered(LibraryFilters(source = LibrarySourceFilter.GIT)))
        assertEquals(listOf(item), model.catalog.filtered(LibraryFilters(updatesAvailable = true),
            updateSkillIds = setOf("subagent:review")))
        assertTrue(model.catalog.filtered(LibraryFilters(updatesAvailable = true),
            updateSkillIds = setOf("review")).isEmpty())
    }
}
