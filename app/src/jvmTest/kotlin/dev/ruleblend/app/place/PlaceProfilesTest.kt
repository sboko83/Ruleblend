package dev.ruleblend.app.place

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.integration.ProfileAttachMode
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.usecase.SkipReason
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule

class PlaceProfilesTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: IntegrationModel

    private fun agent() = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-profiles")
        project = root.resolve("atlas").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        val configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
        model = IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent()),
            backupService = BackupService(root.resolve("backups")),
            service = IntegrationService(blockResolver = repository::loadBlock),
            mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
        ).also { loaded ->
            runBlocking { loaded.load() }
            runBlocking { loaded.select(loaded.projectTargets.single()) }
        }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `header attaches a profile by merging and a project-key switch turns it off`() {
        val base = repository.saveBlock(Block("base-rule", "Base rule", content = "Keep the base.\n"))
        repository.saveBlock(Block("review-rule", "Review rule", content = "Review every change.\n"))
        repository.saveProfile(Profile("review", "Review", blockIds = listOf("review-rule")))
        runBlocking { model.load() }
        runBlocking { model.select(model.projectTargets.single()) }
        runBlocking { model.install(base) }

        compose.setContent { RuleblendTheme { PlaceFileColumn(model) } }
        compose.onNodeWithTag("place-profile-add").performClick()
        compose.onNodeWithTag("place-profile-choice-review").assertIsDisplayed().performClick()
        compose.onNodeWithText("Merge").performClick()
        compose.waitUntil(5_000) { model.profileBindings.singleOrNull()?.id == "review" || model.failure != null }
        assertEquals(null, model.failure?.cause)

        assertTrue(project.resolve("AGENTS.md").readText().contains("Review every change."))
        assertTrue(project.resolve("AGENTS.md").readText().contains("Keep the base."))
        runBlocking { model.setProfileActive(project.toString(), "review", active = false) }
        compose.waitUntil(5_000) { model.profileBindings.singleOrNull()?.active == false }

        assertFalse(project.resolve("AGENTS.md").readText().contains("Review every change."))
        assertTrue(project.resolve("AGENTS.md").readText().contains("Keep the base."))
    }

    @Test
    fun `replace removes base objects before attaching and reconciling the profile`() = runBlocking {
        val base = repository.saveBlock(Block("base-rule", "Base rule", content = "Keep the base.\n"))
        val profileRule = repository.saveBlock(Block("testing-rule", "Testing rule", content = "Run the tests.\n"))
        val profile = repository.saveProfile(Profile("testing", "Testing", blockIds = listOf(profileRule.id)))
        model.install(base)

        model.attachProfile(model.selected as ProjectTarget, profile, ProfileAttachMode.REPLACE)

        val text = project.resolve("AGENTS.md").readText()
        assertFalse(text.contains("Keep the base."))
        assertTrue(text.contains("Run the tests."))
        assertEquals(listOf("testing"), model.profileBindings.map { it.id })
        assertTrue(model.profileBindings.single().active)
    }

    /** Attaching must be reversible: detaching leaves exactly what switching the profile off leaves. */
    @Test
    fun `detaching a profile removes its members and drops the binding`() = runBlocking {
        val base = repository.saveBlock(Block("base-rule", "Base rule", content = "Keep the base.\n"))
        val profileRule = repository.saveBlock(Block("testing-rule", "Testing rule", content = "Run the tests.\n"))
        val profile = repository.saveProfile(Profile("testing", "Testing", blockIds = listOf(profileRule.id)))
        model.install(base)
        model.attachProfile(model.selected as ProjectTarget, profile, ProfileAttachMode.MERGE)

        model.detachProfile(model.selected as ProjectTarget, profile.id)

        val text = project.resolve("AGENTS.md").readText()
        assertFalse(text.contains("Run the tests."))
        assertTrue(text.contains("Keep the base."))
        assertEquals(emptyList(), model.profileBindings)
    }

    /** A protected copy has no other voice: the switch must hand the screen something to show. */
    @Test
    fun `a hand-edited profile copy is reported when the profile is switched off`() = runBlocking {
        val profileRule = repository.saveBlock(Block("testing-rule", "Testing rule", content = "Run the tests.\n"))
        val profile = repository.saveProfile(Profile("testing", "Testing", blockIds = listOf(profileRule.id)))
        model.attachProfile(model.selected as ProjectTarget, profile, ProfileAttachMode.MERGE)
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Run the tests.", "Edited by hand."))

        model.setProfileActive(model.selected as ProjectTarget, profile.id, active = false)

        assertEquals(mapOf(SkipReason.MODIFIED to 1), model.profileReport?.skipped)
        assertTrue(file.readText().contains("Edited by hand."))
    }
}
