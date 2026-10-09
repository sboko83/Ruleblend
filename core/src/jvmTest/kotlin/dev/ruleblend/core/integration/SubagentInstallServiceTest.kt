package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.BlockType
import kotlin.io.path.readText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentInstallServiceTest {
    private lateinit var home: Path
    private lateinit var project: Path
    private lateinit var state: SubagentInstallStateStore
    private lateinit var service: SubagentInstallService

    private val subagent = Block(
        id = "reviewer",
        name = "Reviewer",
        description = "Reviews Kotlin changes",
        type = BlockType.SUBAGENT,
        variants = mapOf(
            "claude-code" to SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read")),
            "codex" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
        ),
        content = "# Review\n\nCheck every change.\n",
    )

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-subagent-home")
        project = Files.createTempDirectory("ruleblend-subagent-project")
        listOf(".claude", ".codex", ".kimi-code", ".zcode", ".pi/agent").forEach { home.resolve(it).createDirectories() }
        state = SubagentInstallStateStore(home.resolve(".ruleblend"))
        service = SubagentInstallService(state)
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `installs the exact native render and fingerprints its bytes`() {
        val claude = ClaudeCodeAdapter(home)
        val codex = CodexAdapter(home)
        val kimi = KimiCodeAdapter(home)

        service.install(AgentGlobalTarget(claude), subagent)
        service.install(AgentGlobalTarget(codex), subagent)
        service.install(AgentGlobalTarget(kimi), subagent)

        assertRendered(claude, ClaudeCodeSubagentFormat)
        assertRendered(codex, CodexSubagentFormat)
        assertRendered(kimi, KimiCodeSubagentFormat)
        assertEquals(SubagentInstallStatus(InstallStatus.SYNCED, false), service.status(AgentGlobalTarget(codex), subagent))
    }

    @Test
    fun `each assistant receives its own variant and nothing is invented for one without`() {
        val claude = ClaudeCodeAdapter(home)
        val codex = CodexAdapter(home)
        val kimi = KimiCodeAdapter(home)

        service.install(AgentGlobalTarget(claude), subagent)
        service.install(AgentGlobalTarget(codex), subagent)
        service.install(AgentGlobalTarget(kimi), subagent)

        val claudeText = requireNotNull(claude.globalSubagentFile(subagent.id)).readText()
        val codexText = requireNotNull(codex.globalSubagentFile(subagent.id)).readText()
        val kimiText = requireNotNull(kimi.globalSubagentFile(subagent.id)).readText()

        assertTrue("model: \"haiku\"" in claudeText, claudeText)
        assertTrue("tools: \"Bash, Read\"" in claudeText, claudeText)
        assertTrue("model = \"gpt-5.6-terra\"" in codexText, codexText)
        assertFalse("tools" in codexText, codexText)
        // Kimi Code has no variant here, so it is installed without a model preference at all.
        assertFalse("modelPreference" in kimiText, kimiText)
    }

    @Test
    fun `project targets skip Codex and write Claude Code and Kimi Code files`() {
        val target = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), CodexAdapter(home), KimiCodeAdapter(home)))

        service.install(target, subagent)

        assertTrue(project.resolve(".claude/agents/reviewer.md").exists())
        assertTrue(project.resolve(".kimi-code/agents/reviewer.md").exists())
        assertFalse(project.resolve(".codex/agents/reviewer.toml").exists())
        assertEquals(listOf("codex"), service.unsupportedAgents(target))
    }

    @Test
    fun `adopted foreign file becomes update available and later manual drift is modified`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = requireNotNull(target.agent.globalSubagentFile(subagent.id))
        file.parent.createDirectories()
        file.writeText("# Foreign reviewer\n")

        assertEquals(SubagentInstallStatus(InstallStatus.MODIFIED, true), service.status(target, subagent))
        service.adopt(target, subagent)
        assertEquals(SubagentInstallStatus(InstallStatus.UPDATE_AVAILABLE, false), service.status(target, subagent))

        service.install(target, subagent)
        assertEquals(SubagentInstallStatus(InstallStatus.SYNCED, false), service.status(target, subagent))

        file.writeText("# Manual drift\n")
        assertEquals(SubagentInstallStatus(InstallStatus.MODIFIED, false), service.status(target, subagent))
        assertFailsWith<IllegalArgumentException> { service.remove(target, subagent) }
        assertTrue(file.exists())
    }

    @Test
    fun `unknown files are never overwritten unless explicit and unsupported agents stay unsupported`() {
        val claudeTarget = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val foreign = requireNotNull(claudeTarget.agent.globalSubagentFile(subagent.id))
        foreign.parent.createDirectories()
        foreign.writeText("# Foreign reviewer\n")

        assertFailsWith<IllegalArgumentException> { service.install(claudeTarget, subagent) }
        service.install(claudeTarget, subagent, overwrite = true)

        listOf(AgentGlobalTarget(ZCodeAdapter(home)), AgentGlobalTarget(PiAdapter(home))).forEach { target ->
            assertFalse(service.appliesTo(target))
            assertNull(service.status(target, subagent))
            assertEquals(listOf(target.agent.id), service.unsupportedAgents(target))
            assertFailsWith<IllegalArgumentException> { service.install(target, subagent) }
        }
    }

    @Test
    fun `releasing keeps the file and leaves it foreign even when it equals the render`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = requireNotNull(target.agent.globalSubagentFile(subagent.id))
        service.install(target, subagent)
        val bytes = file.readBytes().toList()

        service.release(target, subagent.id)

        assertEquals(bytes, file.readBytes().toList())
        assertNull(state.find(skillTargetKey(target), target.agent.id, subagent.id))
        assertEquals(SubagentInstallStatus(InstallStatus.MODIFIED, true), service.status(target, subagent))
        assertFailsWith<IllegalArgumentException> { service.remove(target, subagent) }
        assertEquals(bytes, file.readBytes().toList())
        assertFailsWith<IllegalArgumentException> { service.release(target, subagent.id) }
    }

    @Test
    fun `a released file is taken back under the same block`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        service.install(target, subagent)
        service.release(target, subagent.id)
        val entry = service.entries(target).single()

        service.takeOwnership(target, entry, subagent)

        assertEquals(SubagentInstallStatus(InstallStatus.SYNCED, false), service.status(target, subagent))
    }

    private fun assertRendered(agent: AgentAdapter, format: SubagentFormat) {
        val target = AgentGlobalTarget(agent)
        val file = requireNotNull(agent.globalSubagentFile(subagent.id))
        val expected = format.render(subagent, subagent.variant(agent.id)).encodeToByteArray()
        val record = requireNotNull(state.find(skillTargetKey(target), agent.id, subagent.id))

        assertEquals(expected.toList(), file.readBytes().toList())
        assertEquals(subagentFingerprint(expected), record.fingerprint)
    }

    @Test
    fun `an update without an origin keeps the one the definition was installed with`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        service.install(target, subagent, origin = "g=bundle")
        service.install(target, subagent)
        assertEquals("g=bundle", service.installedOrigins(target)[subagent.id])
    }

    @Test
    fun `an orphaned definition goes when the library block it came from is deleted`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        service.install(target, subagent)

        val outcome = service.removeOrphan(target, subagent.id)

        assertEquals(OrphanRemoval.REMOVED, outcome)
        assertFalse(home.resolve(".claude/agents/reviewer.md").exists())
        assertTrue(service.installedOrigins(target).isEmpty())
    }

    @Test
    fun `a hand-edited orphan definition stays and is reported`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        service.install(target, subagent)
        home.resolve(".claude/agents/reviewer.md").writeText("edited by hand\n")

        val outcome = service.removeOrphan(target, subagent.id)

        assertEquals(OrphanRemoval.PROTECTED, outcome)
        assertEquals("edited by hand\n", home.resolve(".claude/agents/reviewer.md").readText())
    }

    @Test
    fun `removing a renamed orphan leaves a foreign definition with its library id`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val orphan = home.resolve(".claude/agents/community.md").also { it.parent.createDirectories() }
        val orphanBytes = "---\nname: Saved reviewer\n---\n\nReview.\n".encodeToByteArray()
        orphan.toFile().writeBytes(orphanBytes)
        val foreign = home.resolve(".claude/agents/saved-reviewer.md")
        foreign.writeText("# Foreign reviewer\n")
        state.record(
            SubagentInstallRecord(
                skillTargetKey(target),
                "claude-code",
                "saved-reviewer",
                subagentFingerprint(orphanBytes),
                fileName = "community",
            ),
        )

        assertEquals(OrphanRemoval.REMOVED, service.removeOrphan(target, "saved-reviewer"))
        assertFalse(orphan.exists())
        assertEquals("# Foreign reviewer\n", foreign.readText())
    }

    @Test
    fun `entries keep only the agents format and fall back to the file name`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val directory = home.resolve(".claude/agents").also { it.createDirectories() }
        directory.resolve("reviewer.md").writeText("---\n---\n\n# Review\n")
        directory.resolve("ignored.toml").writeText("developer_instructions = \"Ignore\"\n")

        val entry = service.entries(target).single()

        assertEquals("claude-code", entry.agentId)
        assertEquals("reviewer", entry.id)
        assertEquals("reviewer", entry.meta?.name)
        assertEquals("# Review\n", entry.meta?.content)
    }

    @Test
    fun `entries retain an unrecognized definition without parsed metadata`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = home.resolve(".claude/agents/raw.md")
        file.parent.createDirectories()
        file.writeText("# Hand-written agent\n")

        val entry = service.entries(target).single()

        assertEquals("raw", entry.id)
        assertNull(entry.meta)
        assertEquals("# Hand-written agent\n", entry.text)
    }

    @Test
    fun `taking a parsed foreign file preserves its bytes and maps a renamed library id`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = home.resolve(".claude/agents/community.md")
        file.parent.createDirectories()
        val text = "---\nname: Community reviewer\ndescription: Reviews community changes\n---\n\nCheck it.\n"
        file.writeText(text)
        val entry = service.entries(target).single()
        val captured = subagent.copy(id = "community-2", name = "Community reviewer", content = requireNotNull(entry.meta).content)

        service.takeOwnership(target, entry, captured)

        assertEquals(text, file.readText())
        assertEquals(
            "community",
            state.find(skillTargetKey(target), "claude-code", "community-2")?.fileName,
        )
        val classified = service.classifiedEntries(target, setOf("community-2")).single()
        assertEquals("community-2", classified.libraryId)
        assertEquals(PlaceEntryOrigin.MANAGED, classified.origin)
    }

    @Test
    fun `a foreign file saying what the library says is synced in its own layout`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = home.resolve(".claude/agents/tester.md")
        file.parent.createDirectories()
        // Fields in hand-written order and no blank line under the header: not the render's bytes.
        val text = "---\nname: tester\ndescription: Runs tests\ntools: Bash, Read\nmodel: haiku\n---\n# Tests\n"
        file.writeText(text)
        val entry = service.entries(target).single()
        val meta = requireNotNull(entry.meta)
        val captured = Block(
            id = "tester",
            name = "tester",
            description = "Runs tests",
            type = BlockType.SUBAGENT,
            variants = mapOf("claude-code" to meta.toVariant()),
            content = meta.content,
        )

        service.takeOwnership(target, entry, captured)

        assertEquals(text, file.readText())
        assertEquals(SubagentInstallStatus(InstallStatus.SYNCED, false), service.status(target, captured))
        val changed = captured.copy(content = "# Tests\n\nRun them all.\n")
        assertEquals(SubagentInstallStatus(InstallStatus.UPDATE_AVAILABLE, false), service.status(target, changed))
        file.writeText(text + "Hand edit.\n")
        assertEquals(SubagentInstallStatus(InstallStatus.MODIFIED, false), service.status(target, captured))
    }

    @Test
    fun `adopting a file that differs in meaning stays an available update`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = requireNotNull(target.agent.globalSubagentFile(subagent.id))
        file.parent.createDirectories()
        file.writeText("---\nname: Reviewer\ndescription: Reviews Kotlin changes\ntools: Bash, Read\nmodel: sonnet\n---\n\n${subagent.content}")

        service.adopt(target, subagent)

        assertNull(state.find(skillTargetKey(target), "claude-code", subagent.id)?.equivalentRender)
        assertEquals(SubagentInstallStatus(InstallStatus.UPDATE_AVAILABLE, false), service.status(target, subagent))
    }

    @Test
    fun `adopting a reordered but equal file is synced`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = requireNotNull(target.agent.globalSubagentFile(subagent.id))
        file.parent.createDirectories()
        file.writeText("---\nname: Reviewer\ndescription: Reviews Kotlin changes\ntools: Bash, Read\nmodel: haiku\n---\n\n${subagent.content}")

        service.adopt(target, subagent)

        assertEquals(SubagentInstallStatus(InstallStatus.SYNCED, false), service.status(target, subagent))
    }

    @Test
    fun `taking an unparsed foreign file is refused`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        val file = home.resolve(".claude/agents/raw.md")
        file.parent.createDirectories()
        file.writeText("# Hand-written agent\n")
        val entry = service.entries(target).single()

        assertFailsWith<IllegalArgumentException> { service.takeOwnership(target, entry, subagent) }
        assertNull(state.find(skillTargetKey(target), "claude-code", subagent.id))
    }

    @Test
    fun `another agents sidecar does not claim a definition`() {
        val target = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), KimiCodeAdapter(home)))
        val file = project.resolve(".claude/agents/reviewer.md")
        file.parent.createDirectories()
        file.writeText(ClaudeCodeSubagentFormat.render(subagent))
        state.record(SubagentInstallRecord(skillTargetKey(target), "kimi-code", subagent.id, subagentFingerprint(file.readBytes())))

        val entry = service.classifiedEntries(target, setOf(subagent.id)).single()

        assertEquals(PlaceEntryOrigin.FOREIGN, entry.origin)
        assertEquals(subagentPlaceEntryKey(file), entry.key)
    }

    @Test
    fun `deleting the library subagent makes its definition orphaned`() {
        val target = AgentGlobalTarget(ClaudeCodeAdapter(home))
        service.install(target, subagent)

        assertEquals(PlaceEntryOrigin.MANAGED, service.classifiedEntries(target, setOf(subagent.id)).single().origin)
        assertEquals(PlaceEntryOrigin.ORPHAN, service.classifiedEntries(target, emptySet()).single().origin)
    }
}
