package dev.ruleblend.app.assistance

import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentCli
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import dev.ruleblend.mcp.AgentIntegrationState
import dev.ruleblend.mcp.BundledMcpState
import dev.ruleblend.mcp.BundledSkillState
import dev.ruleblend.mcp.McpRegistration
import dev.ruleblend.mcp.skill.SkillStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AssistanceStartupTest {
    private val claude = agent("claude-code", "claude")
    private val codex = agent("codex", "codex")
    private val probe = CliProbe("/path with spaces/Юникод/claude", true, "/login/bin:/usr/bin")

    @Test fun `saved object draft carries exact identity and excludes its body`() {
        val block = Block(id = "commit", name = "Commit", content = "PRIVATE_BODY")
        val target = AssistanceObject.fromSaved(block)
        assertEquals("Обсудим правило Commit (ID: commit). Используй Ruleblend MCP и skill ruleblend. " +
            "Сначала обсудим задачу.", target.draft(Language.RU))
        assertEquals("Let's discuss the rule Commit (ID: commit). Use Ruleblend MCP and the ruleblend skill. " +
            "Let's discuss the task first.", target.draft(Language.EN))
        assertFalse(target.draft(Language.EN).contains(block.content))
        for (type in BlockType.entries) {
            val draft = AssistanceObject.fromSaved(block.copy(type = type)).draft(Language.EN)
            assertTrue(draft.contains("(ID: commit)"))
        }
        val skill = Skill(id = "skill-id", name = "My skill", content = "PRIVATE_SKILL")
        assertTrue(AssistanceObject.fromSaved(skill).draft(Language.RU).contains("скилл My skill (ID: skill-id)"))
    }

    @Test fun `missing identities and terminal controls cannot enter a draft`() {
        for (invalid in listOf("", " ", "name\nEnter", "name\rEnter", "name\u001b[201~", "name\u0000")) {
            assertFailsWith<IllegalArgumentException> { AssistanceObject(AssistanceObject.Kind.RULE, invalid, "Commit") }
            assertFailsWith<IllegalArgumentException> { AssistanceObject(AssistanceObject.Kind.RULE, "commit", invalid) }
        }
        val name = "Юникод 'quoted' $(literal)"
        assertTrue(AssistanceObject(AssistanceObject.Kind.RULE, "id", name).draft(Language.RU).contains(name))
    }

    @Test fun `only complete current connections with a CLI are ready`() = runBlocking {
        for (mcp in McpRegistration.entries) {
            for (skill in SkillStatus.entries) {
                val result = discoverAssistanceAssistants(listOf(claude), { listOf(state(claude.id, mcp, skill)) }) { probe }
                assertEquals(mcp == McpRegistration.REGISTERED && skill == SkillStatus.INSTALLED,
                    result.isNotEmpty(), "$mcp / $skill")
            }
        }
        for (state in listOf(AgentIntegrationState(claude.id),
            state(claude.id).copy(mcp = null), state(claude.id).copy(skill = null))) {
            assertTrue(discoverAssistanceAssistants(listOf(claude), { listOf(state) }) { probe }.isEmpty())
        }
        assertTrue(discoverAssistanceAssistants(listOf(claude), { emptyList() }) { probe }.isEmpty())
        assertTrue(discoverAssistanceAssistants(listOf(claude), { listOf(state(claude.id)) }) { null }.isEmpty())
        assertTrue(discoverAssistanceAssistants(listOf(claude), { listOf(state(claude.id)) }) { error("Unavailable") }.isEmpty())
    }

    @Test fun `discovery keeps the login PATH and does not use assistant visibility`() = runBlocking {
        val calls = mutableListOf<String>()
        val other = agent("other", "other")
        val noCli = agent("claude-code", null)
        val states = listOf(state(claude.id), state(codex.id), state(other.id))
        val ready = discoverAssistanceAssistants(listOf(claude, codex, other), { states }) {
            calls += it
            if (it == "claude") probe else null
        }
        assertEquals(listOf("claude"), calls)
        assertEquals(listOf(ReadyAssistanceAssistant(claude.id, claude.name, probe)), ready)
        assertTrue(discoverAssistanceAssistants(listOf(noCli), { states }) { probe }.isEmpty())
        assertTrue(discoverAssistanceAssistants(listOf(codex), { states }) { probe }.isEmpty())
    }

    @Test fun `unselected or unavailable assistants never become implicit defaults`() {
        val ready = listOf(ReadyAssistanceAssistant(claude.id, claude.name, probe),
            ReadyAssistanceAssistant(codex.id, codex.name, probe.copy(path = "/bin/codex")))
        assertEquals(AssistanceSelection.Configure, selectAssistanceAssistant(emptyList(), ready))
        assertEquals(AssistanceSelection.Configure, selectAssistanceAssistant(listOf("missing"), ready))
        assertEquals(AssistanceSelection.Configure, selectAssistanceAssistant(listOf(claude.id), emptyList()))
        assertEquals(AssistanceSelection.Launch(ready[0]), selectAssistanceAssistant(listOf(claude.id, claude.id), ready))
        assertEquals(AssistanceSelection.Launch(ready[1]), selectAssistanceAssistant(listOf("missing", codex.id), ready))
        assertEquals(AssistanceSelection.Choose(ready), selectAssistanceAssistant(listOf(codex.id, claude.id), ready))
    }

    @Test fun `Claude native prefill uses direct argv and normal profile without submission`() {
        val assistant = ReadyAssistanceAssistant(claude.id, claude.name, probe)
        val target = AssistanceObject.fromSaved(Block("commit", "Commit 'quotes' $(literal)"))
        val directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("space Юникод")
        val launch = prepareClaudeAssistanceLaunch(assistant, target, Language.RU, directory)
        assertEquals(listOf(probe.path, "--prefill", target.draft(Language.RU)), launch.command)
        assertEquals(directory, launch.directory)
        assertEquals(mapOf("PATH" to probe.searchPath), launch.environmentOverrides)
        assertTrue(prepareClaudeAssistanceLaunch(assistant.copy(cli = probe.copy(searchPath = null)),
            target, Language.EN, directory).environmentOverrides.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            prepareClaudeAssistanceLaunch(assistant.copy(id = "codex"), target, Language.EN, directory)
        }
        assertFailsWith<IllegalArgumentException> {
            prepareClaudeAssistanceLaunch(assistant, target, Language.EN, Path.of("relative"))
        }
    }

    @Test fun `project targets carry their exact starting text and folder`() {
        val directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("space Юникод")
        val help = AssistanceObject.fromProject(directory)
        val profile = AssistanceObject.fromProject(directory, AssistanceObject.Kind.PROFILE)
        assertEquals(AssistanceObject.Kind.PROJECT, help.kind)
        assertEquals(AssistanceObject.Kind.PROFILE, profile.kind)
        assertEquals("space Юникод", help.name)
        assertEquals("Project space Юникод ($directory). I need help with ", help.draft(Language.EN))
        assertEquals("Проект space Юникод ($directory). Нужна помощь с ", help.draft(Language.RU))
        assertEquals("Let's pick a profile for the project space Юникод ($directory). " +
            "Use Ruleblend MCP and the ruleblend skill. Let's discuss first.", profile.draft(Language.EN))
        assertEquals("Подберём профиль для проекта space Юникод ($directory). " +
            "Используй Ruleblend MCP и skill ruleblend. Сначала обсудим.", profile.draft(Language.RU))
        val assistant = ReadyAssistanceAssistant(claude.id, claude.name, probe)
        for (target in listOf(help, profile)) {
            val launch = prepareClaudeAssistanceLaunch(assistant, target, Language.EN, directory)
            assertEquals(listOf(probe.path, "--prefill", target.draft(Language.EN)), launch.command)
            assertEquals(directory, launch.directory)
            assertEquals(target.kind, launch.target.kind)
        }
        assertFailsWith<IllegalArgumentException> { AssistanceObject.fromProject(Path.of("relative")) }
        assertFailsWith<IllegalArgumentException> { AssistanceObject.fromProject(directory, AssistanceObject.Kind.RULE) }
    }

    private fun state(id: String, mcp: McpRegistration = McpRegistration.REGISTERED,
                      skill: SkillStatus = SkillStatus.INSTALLED) = AgentIntegrationState(
        id, BundledMcpState(mcp, 1), BundledSkillState(skill, 1),
    )

    private fun agent(id: String, executable: String?): AgentAdapter = object : AgentAdapter {
        override val id = id
        override val name = id
        override val cli = executable?.let { AgentCli(it) }
        override fun isAvailable() = true
        override fun globalFile(): Path = Path.of("unused")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("unused")
    }
}
