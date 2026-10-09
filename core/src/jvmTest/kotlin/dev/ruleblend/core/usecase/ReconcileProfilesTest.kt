package dev.ruleblend.core.usecase

import dev.ruleblend.core.blockFileReplacement
import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.McpStatus
import dev.ruleblend.core.integration.McpWrite
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReconcileProfilesTest {

    private lateinit var root: Path
    private lateinit var projectDir: Path
    private lateinit var repository: LibraryRepository
    private lateinit var config: ConfigStore
    private lateinit var rules: IntegrationService
    private lateinit var target: ProjectTarget

    private val rule = Block(id = "testing", name = "Testing", content = "Write the tests.")

    private val agent = object : AgentAdapter {
        override val id = "test"
        override val name = "Test"
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("global/AGENTS.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-profiles")
        projectDir = root.resolve("project").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        config = ConfigStore(root.resolve("config.json"))
        rules = IntegrationService(blockResolver = repository::loadBlock)
        target = ProjectTarget(projectDir, listOf(agent))
        repository.saveBlock(rule)
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(root)
    }

    @Test
    fun enablingAProfileInstallsItsMembersWithTheProfileOrigin() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))

        val report = reconcile()(target)

        assertEquals(1, report.installed)
        assertEquals("p=review", rules.installedOrigins(target)[rule.id])
        assertTrue(projectDir.resolve("AGENTS.md").readText().contains(rule.content))
    }

    @Test
    fun profileChangesRespectMcpProtectionAtTheWrite() {
        val server = repository.saveBlock(Block("server", "Server", type = BlockType.MCP, content = "{}"))
        repository.saveProfile(Profile("servers", "Servers", blockIds = listOf(server.id)))
        var installed = false
        val mcp = object : McpBlockService by McpBlockService.None {
            override fun installable(target: Target, block: Block) = true
            override fun status(target: Target, block: Block): McpStatus? = null
            override fun installedOrigins(target: Target): Map<String, String?> =
                if (installed) mapOf(server.id to "p=servers") else emptyMap()
            override fun install(target: Target, block: Block, origin: String?) = McpWrite.MODIFIED
            override fun remove(target: Target, block: Block) = McpWrite.MODIFIED
        }
        val reconcile = ReconcileProfiles(repository, config, rules, InstallPolicy(rules, mcp, config = config::load), mcp)
        bind(ProfileBinding("servers", active = true))

        val install = reconcile(target)
        assertEquals(0, install.failed)
        assertEquals(1, install.skipped[SkipReason.MODIFIED])

        installed = true
        bind(ProfileBinding("servers", active = false))
        val remove = reconcile(target)
        assertEquals(0, remove.failed)
        assertEquals(1, remove.skipped[SkipReason.MODIFIED])
    }

    @Test
    fun disablingAProfileRemovesOnlyItsMembers() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))
        reconcile()(target)
        bind(ProfileBinding("review", active = false))

        val report = reconcile()(target)

        assertEquals(1, report.removed)
        assertFalse(rule.id in rules.installedOrigins(target))
    }

    @Test
    fun overlappingProfilesInstallOnceAndMoveTheOriginToTheRemainingProfile() {
        saveProfile("first")
        saveProfile("second")
        bind(ProfileBinding("first", active = true), ProfileBinding("second", active = true))
        val enabled = reconcile()(target)
        bind(ProfileBinding("first", active = false), ProfileBinding("second", active = true))

        val afterFirstIsDisabled = reconcile()(target)

        assertEquals(1, enabled.installed)
        assertEquals(1, afterFirstIsDisabled.retagged)
        assertEquals("p=second", rules.installedOrigins(target)[rule.id])
        assertEquals(1, rules.regions(projectDir.resolve("AGENTS.md")).count { it.id == rule.id })
    }

    @Test
    fun updatingAProfileCopyKeepsItOwnedByTheProfile() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))
        reconcile()(target)

        rules.install(target, rule.copy(content = "Write the tests first.", version = 2))
        assertEquals("p=review", rules.installedOrigins(target)[rule.id])
        bind(ProfileBinding("review", active = false))
        val report = reconcile()(target)

        assertEquals(1, report.removed)
        assertFalse(rule.id in rules.installedOrigins(target))
    }

    @Test
    fun aBaseCopyWinsOverAnActiveProfile() {
        saveProfile("review")
        rules.install(target, rule)
        bind(ProfileBinding("review", active = true))

        val report = reconcile()(target)

        assertEquals(1, report.unchanged)
        assertEquals(null, rules.installedOrigins(target)[rule.id])
    }

    @Test
    fun aHandEditedProfileCopyIsReportedAndLeftInPlaceWhenDisabled() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))
        reconcile()(target)
        val file = projectDir.resolve("AGENTS.md")
        file.writeText(file.readText().replace(rule.content, "Edited by hand."))
        bind(ProfileBinding("review", active = false))

        val report = reconcile()(target)

        assertEquals(1, report.skipped[SkipReason.MODIFIED])
        assertIs<ProfileReconcileOutcome.Skipped>(report.entries.single().outcome)
        assertTrue(file.readText().contains("Edited by hand."))
    }

    @Test
    fun `a profile copy is taken out when its library object is deleted`() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))
        reconcile()(target)
        repository.deleteBlock(rule.id)

        val report = reconcile()(target)

        assertEquals(1, report.removed)
        assertEquals(0, report.failed)
        assertFalse(rule.id in rules.installedOrigins(target))
        assertFalse(projectDir.resolve("AGENTS.md").readText().contains(rule.content))
    }

    @Test
    fun `a hand-edited copy of a deleted library object is reported and left in place`() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))
        reconcile()(target)
        val file = projectDir.resolve("AGENTS.md")
        file.writeText(file.readText().replace(rule.content, "Edited by hand."))
        repository.deleteBlock(rule.id)

        val report = reconcile()(target)

        assertEquals(1, report.skipped[SkipReason.MODIFIED])
        assertEquals(0, report.failed)
        assertTrue(file.readText().contains("Edited by hand."))
    }

    @Test
    fun `a rule that cannot be written into every file of the project is reported as failed`() {
        saveProfile("review")
        bind(ProfileBinding("review", active = true))
        val blocked = projectDir.resolve("second").also { it.createDirectories() }
        val twoFiles = ProjectTarget(projectDir, listOf(agent, agent("second", blocked.resolve("AGENTS.md"))))
        blockFileReplacement(blocked.resolve("AGENTS.md")).use {
            val report = reconcile()(twoFiles)

            assertEquals(0, report.installed)
            assertEquals(1, report.entries.count { it.outcome is ProfileReconcileOutcome.Failed })
            assertFalse(projectDir.resolve("AGENTS.md").exists(), "the first file was put back")
        }
    }

    private fun agent(id: String, file: Path) = object : AgentAdapter {
        override val id = id
        override val name = id
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("global/$id.md")
        override fun projectFile(projectDir: Path): Path = file
    }

    private fun reconcile() = ReconcileProfiles(
        repository = repository,
        configStore = config,
        rules = rules,
        policy = InstallPolicy(rules, config = config::load),
    )

    private fun saveProfile(id: String) {
        repository.saveProfile(Profile(id = id, name = id, blockIds = listOf(rule.id)))
    }

    private fun bind(vararg bindings: ProfileBinding) {
        config.update { it.copy(projectProfiles = mapOf(projectDir.toString() to bindings.toList())) }
    }
}
