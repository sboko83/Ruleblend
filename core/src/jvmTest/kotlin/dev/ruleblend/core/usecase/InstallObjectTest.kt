package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.McpStatus
import dev.ruleblend.core.integration.McpWrite
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The safety policy used to live in three places at once — the Place surface, the bulk installer and
 * the MCP facade — and had already drifted apart between them. These tests pin it down in the one
 * place that now answers: what is refused, what is written anyway, and what the caller is told.
 */
class InstallObjectTest {

    private lateinit var root: Path
    private lateinit var atlas: Path
    private lateinit var beacon: Path
    private lateinit var configStore: ConfigStore
    private val mcp = FakeMcp()

    private val shared = Block(id = "shared", name = "Shared", version = 1, content = "Shared body.")
    private val pinned = Block(id = "pinned", name = "Pinned", version = 1, content = "Pinned body.")
    private val server = Block(id = "server", name = "Server", type = BlockType.MCP, version = 1, content = "{}")

    /** A forced write rebuilds the whole managed run from the library, so it needs the library. */
    private val library = listOf(shared, pinned).associateBy { it.id }
    private val service = IntegrationService(blockResolver = library::get)

    private val agent = object : AgentAdapter {
        override val id = "test"
        override val name = "Test"
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("global/AGENTS.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-usecase")
        atlas = root.resolve("atlas").also { it.createDirectories() }
        beacon = root.resolve("beacon").also { it.createDirectories() }
        root.resolve("global").createDirectories()
        configStore = ConfigStore(root.resolve("config.json"))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun policy(skills: Boolean = false) =
        InstallPolicy(
            service,
            mcp,
            if (skills) error("no skill installer in these tests") else null,
            config = { configStore.load() },
        )

    private fun install() = InstallObject(service, mcp, null, policy())

    private fun remove() = RemoveObject(service, mcp, null, policy())

    private fun project(dir: Path) = ProjectTarget(dir, listOf(agent))

    private fun agentTarget() = AgentGlobalTarget(agent)

    @Test
    fun writesEveryObjectIntoEveryTarget() {
        val report = install()(
            InstallCommand(listOf(project(atlas), project(beacon)), listOf(InstallItem.Rule(shared))),
        )

        assertEquals(2, report.written)
        assertEquals(0, report.skippedTotal)
        assertEquals(0, report.failed)
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Shared body."))
        assertTrue(beacon.resolve("AGENTS.md").readText().contains("Shared body."))
    }

    /** A moved or deleted project must not be rebuilt around a single instruction file. */
    @Test
    fun missingProjectFolderFailsWithoutRecreatingIt() {
        val gone = root.resolve("gone")

        val report = install()(
            InstallCommand(listOf(project(gone), project(atlas)), listOf(InstallItem.Rule(shared))),
        )

        assertEquals(1, report.failed)
        assertEquals(1, report.written)
        assertTrue(Files.notExists(gone))
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Shared body."))
    }

    @Test
    fun projectScopedRuleStaysOutOfEveryOtherPlace() {
        configStore.update { it.withRuleScope(pinned.id, atlas) }

        val report = install()(
            InstallCommand(listOf(project(beacon), agentTarget()), listOf(InstallItem.Rule(pinned))),
        )

        assertEquals(0, report.written)
        assertEquals(mapOf(SkipReason.OUT_OF_SCOPE to 2), report.skipped)
        assertEquals(listOf("pinned"), report.skippedIds(SkipReason.OUT_OF_SCOPE))
    }

    /** Forcing is a decision about one local copy; it must never widen where a rule may live. */
    @Test
    fun forceDoesNotWidenScope() {
        configStore.update { it.withRuleScope(pinned.id, atlas) }

        val report = install()(
            InstallCommand(listOf(project(beacon)), listOf(InstallItem.Rule(pinned)), force = true),
        )

        assertEquals(mapOf(SkipReason.OUT_OF_SCOPE to 1), report.skipped)
    }

    /** A rule that landed somewhere before it was pinned must still be removable from there. */
    @Test
    fun removalIgnoresScope() {
        install()(InstallCommand(listOf(project(beacon)), listOf(InstallItem.Rule(pinned))))
        configStore.update { it.withRuleScope(pinned.id, atlas) }

        val report = remove()(InstallCommand(listOf(project(beacon)), listOf(InstallItem.Rule(pinned))))

        assertEquals(1, report.written)
        assertEquals(0, report.skippedTotal)
        assertTrue(!beacon.resolve("AGENTS.md").readText().contains("Pinned body."))
    }

    @Test
    fun handEditedRuleIsReportedInsteadOfOverwritten() {
        val target = project(atlas)
        install()(InstallCommand(listOf(target), listOf(InstallItem.Rule(shared))))
        editByHand(atlas.resolve("AGENTS.md"))

        val report = install()(InstallCommand(listOf(target), listOf(InstallItem.Rule(shared))))

        assertEquals(0, report.written)
        assertEquals(mapOf(SkipReason.MODIFIED to 1), report.skipped)
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Edited by hand."))
    }

    /** The same refusal round the other way: taking an object out is not a decision to drop an edit. */
    @Test
    fun handEditedRuleIsNotRemovedEither() {
        val target = project(atlas)
        install()(InstallCommand(listOf(target), listOf(InstallItem.Rule(shared))))
        editByHand(atlas.resolve("AGENTS.md"))

        val report = remove()(InstallCommand(listOf(target), listOf(InstallItem.Rule(shared))))

        assertEquals(mapOf(SkipReason.MODIFIED to 1), report.skipped)
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Edited by hand."))
    }

    @Test
    fun forceOverwritesTheHandEditedRule() {
        val target = project(atlas)
        install()(InstallCommand(listOf(target), listOf(InstallItem.Rule(shared))))
        editByHand(atlas.resolve("AGENTS.md"))

        val report = install()(
            InstallCommand(listOf(target), listOf(InstallItem.Rule(shared)), force = true),
        )

        assertEquals(1, report.written)
        assertEquals(InstallStatus.SYNCED, service.status(target, shared))
        assertTrue(atlas.resolve("AGENTS.md").readText().contains("Shared body."))
    }

    @Test
    fun mcpEntryNoAgentCanRunIsUnsupported() {
        mcp.installable = false

        val report = install()(InstallCommand(listOf(project(atlas)), listOf(InstallItem.Mcp(server))))

        assertEquals(mapOf(SkipReason.UNSUPPORTED to 1), report.skipped)
        assertEquals(emptyList(), mcp.installed)
    }

    @Test
    fun foreignMcpEntryIsRefusedAndForcingTakesIt() {
        mcp.status = McpStatus(InstallStatus.SYNCED, conflict = true)

        assertEquals(
            mapOf(SkipReason.MODIFIED to 1),
            install()(InstallCommand(listOf(project(atlas)), listOf(InstallItem.Mcp(server)))).skipped,
        )
        assertEquals(emptyList(), mcp.installed)

        val forced = install()(
            InstallCommand(listOf(project(atlas)), listOf(InstallItem.Mcp(server)), force = true),
        )

        assertEquals(1, forced.written)
        assertEquals(listOf("server"), mcp.installed)
    }

    @Test
    fun aSkillWithNoInstallerWiredIsUnsupported() {
        val skill = Skill(id = "docs", name = "docs", description = "Docs", content = "# docs")

        val report = install()(InstallCommand(listOf(project(atlas)), listOf(InstallItem.SkillCopy(skill))))

        assertEquals(mapOf(SkipReason.UNSUPPORTED to 1), report.skipped)
    }

    /** A bulk action that gives up halfway leaves a fleet in a state nobody asked for. */
    @Test
    fun oneFailureDoesNotStopTheRest() {
        mcp.failOn = "server"

        val report = install()(
            InstallCommand(
                listOf(project(atlas), project(beacon)),
                listOf(InstallItem.Mcp(server), InstallItem.Rule(shared)),
            ),
        )

        assertEquals(2, report.written)
        assertEquals(2, report.failed)
        assertEquals(listOf("shared"), report.writtenIds())
        assertEquals("no MCP config file here", report.firstError()?.message)
        assertTrue(beacon.resolve("AGENTS.md").readText().contains("Shared body."))
    }

    private fun editByHand(file: Path) {
        file.writeText(file.readText().replace("Shared body.", "Edited by hand."))
    }

    @Test
    fun mcpChangesAfterThePolicyProbeAreNotOverwrittenOrRemoved() {
        for (protection in listOf(McpWrite.MODIFIED, McpWrite.FOREIGN)) {
            mcp.safeWrite = protection
            val installed = install()(project(atlas), InstallItem.Mcp(server))
            val removed = remove()(project(atlas), InstallItem.Mcp(server))

            assertEquals(listOf(server.id), installed.skippedIds(SkipReason.MODIFIED))
            assertEquals(listOf(server.id), removed.skippedIds(SkipReason.MODIFIED))
            assertTrue(mcp.installed.isEmpty())
            assertTrue(mcp.removed.isEmpty())
        }
    }

    @Test
    fun aPolicyFailureIsReportedWithoutAbortingTheOtherObjects() {
        mcp.failProbe = true

        val report = install()(InstallCommand(listOf(project(atlas)), listOf(InstallItem.Mcp(server), InstallItem.Rule(shared))))

        assertEquals(1, report.failed)
        assertEquals(listOf(shared.id), report.writtenIds())
    }

    /** Stands in for the `mcp` module's installer, which `core` must not depend on. */
    private class FakeMcp : McpBlockService {
        var installable = true
        var status: McpStatus? = null
        var failOn: String? = null
        var safeWrite = McpWrite.DONE
        var failProbe = false
        val installed = mutableListOf<String>()
        val removed = mutableListOf<String>()

        override fun appliesTo(target: Target) = installable

        override fun installable(target: Target, block: Block): Boolean {
            check(!failProbe) { "Cannot read capabilities" }
            return installable
        }

        override fun status(target: Target, block: Block): McpStatus? = status

        override fun install(target: Target, block: Block): McpWrite {
            if (safeWrite == McpWrite.DONE) forceInstall(target, block)
            return safeWrite
        }

        override fun remove(target: Target, block: Block): McpWrite {
            if (safeWrite == McpWrite.DONE) forceRemove(target, block.id)
            return safeWrite
        }

        override fun forceInstall(target: Target, block: Block) {
            if (block.id == failOn) error("no MCP config file here")
            installed += block.id
        }

        override fun forceRemove(target: Target, blockId: String) {
            if (blockId == failOn) error("no MCP config file here")
            removed += blockId
        }

        override fun localChange(target: Target, block: Block, agentId: String?) = block.content

        override fun acceptLocalChange(target: Target, saved: Block, expected: String, agentId: String?) = Unit
    }
}
