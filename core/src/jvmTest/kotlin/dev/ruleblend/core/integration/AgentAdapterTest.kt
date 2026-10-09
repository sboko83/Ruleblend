package dev.ruleblend.core.integration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentAdapterTest {

    private lateinit var home: Path

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-home")
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun claudeCodeIsAvailableOnlyWithItsConfigDir() {
        val adapter = ClaudeCodeAdapter(home)

        assertFalse(adapter.isAvailable())
        home.resolve(".claude").createDirectory()
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun codexIsAvailableOnlyWithItsConfigDir() {
        val adapter = CodexAdapter(home)

        assertFalse(adapter.isAvailable())
        home.resolve(".codex").createDirectory()
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun kimiCodeIsAvailableOnlyWithItsConfigDir() {
        val adapter = KimiCodeAdapter(home)

        assertFalse(adapter.isAvailable())
        home.resolve(".kimi-code").createDirectory()
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun zCodeIsAvailableOnlyWithItsConfigDir() {
        val adapter = ZCodeAdapter(home)

        assertFalse(adapter.isAvailable())
        home.resolve(".zcode").createDirectory()
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun piIsAvailableOnlyWithItsConfigDir() {
        val adapter = PiAdapter(home)

        assertFalse(adapter.isAvailable())
        home.resolve(".pi").createDirectory()
        assertFalse(adapter.isAvailable())
        home.resolve(".pi").resolve("agent").createDirectory()
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun piIsAvailableWithoutAnyInstructionFile() {
        home.resolve(".pi").createDirectory()
        home.resolve(".pi").resolve("agent").createDirectory()

        assertTrue(PiAdapter(home).isAvailable())
    }

    @Test
    fun adaptersUseTheirOwnFileNames() {
        val project = home.resolve("project")

        assertEquals(home.resolve(".claude").resolve("CLAUDE.md"), ClaudeCodeAdapter(home).globalFile())
        assertEquals(project.resolve("AGENTS.md"), ClaudeCodeAdapter(home).projectFile(project))
        assertEquals(project.resolve("CLAUDE.md"), ClaudeCodeAdapter(home).projectRedirectFile(project))
        assertEquals(SubagentSupport.SUPPORTED, ClaudeCodeAdapter(home).subagentSupport)
        assertEquals(home.resolve(".claude/agents/review.md"), ClaudeCodeAdapter(home).globalSubagentFile("review"))
        assertEquals(project.resolve(".claude/agents/review.md"), ClaudeCodeAdapter(home).projectSubagentFile(project, "review"))
        assertEquals(home.resolve(".codex").resolve("AGENTS.md"), CodexAdapter(home).globalFile())
        assertEquals(project.resolve("AGENTS.md"), CodexAdapter(home).projectFile(project))
        assertEquals(SubagentSupport.SUPPORTED, CodexAdapter(home).subagentSupport)
        assertEquals(home.resolve(".codex/agents/review.toml"), CodexAdapter(home).globalSubagentFile("review"))
        assertNull(CodexAdapter(home).projectSubagentFile(project, "review"))
        assertEquals(
            listOf(
                SkillDirectory("native", home.resolve(".codex/skills")),
            ),
            CodexAdapter(home).globalSkillDirectories(),
        )
        assertEquals(
            listOf(
                SkillDirectory("native", project.resolve(".codex/skills")),
            ),
            CodexAdapter(home).projectSkillDirectories(project),
        )
        assertEquals(home.resolve(".pi").resolve("agent").resolve("AGENTS.md"), PiAdapter(home).globalFile())
        assertEquals(project.resolve("AGENTS.md"), PiAdapter(home).projectFile(project))
        assertEquals(home.resolve(".agents/skills"), PiAdapter(home).globalSkillsDirectory())
        assertEquals(project.resolve(".agents/skills"), PiAdapter(home).projectSkillsDirectory(project))
        assertEquals(home.resolve(".kimi-code").resolve("AGENTS.md"), KimiCodeAdapter(home).globalFile())
        assertEquals(project.resolve("AGENTS.md"), KimiCodeAdapter(home).projectFile(project))
        assertEquals(SubagentSupport.SUPPORTED, KimiCodeAdapter(home).subagentSupport)
        assertEquals(home.resolve(".kimi-code/agents/review.md"), KimiCodeAdapter(home).globalSubagentFile("review"))
        assertEquals(project.resolve(".kimi-code/agents/review.md"), KimiCodeAdapter(home).projectSubagentFile(project, "review"))
        assertEquals(home.resolve(".kimi-code/skills"), KimiCodeAdapter(home).globalSkillsDirectory())
        assertEquals(project.resolve(".kimi-code/skills"), KimiCodeAdapter(home).projectSkillsDirectory(project))
        assertEquals(
            listOf(
                SkillDirectory("native", home.resolve(".kimi-code/skills")),
                SkillDirectory("shared", home.resolve(".agents/skills")),
            ),
            KimiCodeAdapter(home).globalSkillDirectories(),
        )
        assertEquals(home.resolve(".zcode").resolve("AGENTS.md"), ZCodeAdapter(home).globalFile())
        assertEquals(project.resolve("AGENTS.md"), ZCodeAdapter(home).projectFile(project))
        assertEquals(SubagentSupport.UNSUPPORTED, ZCodeAdapter(home).subagentSupport)
        assertNull(ZCodeAdapter(home).globalSubagentFile("review"))
        assertEquals(
            listOf(
                SkillDirectory("native", home.resolve(".zcode/skills")),
                SkillDirectory("shared", home.resolve(".agents/skills")),
            ),
            ZCodeAdapter(home).globalSkillDirectories(),
        )
        assertEquals(
            listOf(
                SkillDirectory("native", project.resolve(".zcode/skills")),
                SkillDirectory("shared", project.resolve(".agents/skills")),
            ),
            ZCodeAdapter(home).projectSkillDirectories(project),
        )
    }

    @Test
    fun projectTargetWritesOneSharedFileForEveryAgent() {
        val project = home.resolve("project")
        val target = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), CodexAdapter(home)))

        assertEquals(listOf(project.resolve("AGENTS.md")), target.files())
    }

    @Test
    fun claudeGetsAPointerFileInsteadOfACopy() {
        val project = home.resolve("project")
        val target = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), CodexAdapter(home)))

        assertEquals(
            listOf(Redirect(project.resolve("CLAUDE.md"), project.resolve("AGENTS.md"))),
            target.redirects(),
        )
        assertEquals(
            listOf(project.resolve("AGENTS.md"), project.resolve("CLAUDE.md")),
            target.ownedFiles(),
        )
    }

    @Test
    fun aProjectWithoutClaudeHasNoPointerFile() {
        val project = home.resolve("project")

        assertEquals(emptyList(), ProjectTarget(project, listOf(CodexAdapter(home))).redirects())
    }

    @Test
    fun agentGlobalsHaveNoPointerFile() {
        assertEquals(emptyList(), AgentGlobalTarget(ClaudeCodeAdapter(home)).redirects())
        assertEquals(
            listOf(home.resolve(".claude").resolve("CLAUDE.md")),
            AgentGlobalTarget(ClaudeCodeAdapter(home)).ownedFiles(),
        )
    }

    @Test
    fun agentsSharingAFilenameWriteItOnce() {
        val project = home.resolve("project")
        val target = ProjectTarget(project, listOf(CodexAdapter(home), PiAdapter(home)))

        assertEquals(listOf(project.resolve("AGENTS.md")), target.files())
    }

    @Test
    fun agentGlobalsStaySeparatePerAgent() {
        assertEquals(
            listOf(home.resolve(".codex").resolve("AGENTS.md")),
            AgentGlobalTarget(CodexAdapter(home)).files(),
        )
        assertEquals(
            listOf(home.resolve(".pi").resolve("agent").resolve("AGENTS.md")),
            AgentGlobalTarget(PiAdapter(home)).files(),
        )
    }

    @Test
    fun capabilityMatrixNamesGlobalOnlyCodexSubagentsAndUnsupportedAgents() {
        assertEquals(CapabilityState.SUPPORTED, ClaudeCodeAdapter(home).capabilities().subagents.state)
        assertEquals(
            AgentCapability(CapabilityState.PARTIAL, CapabilityLimit.GLOBAL_ONLY),
            CodexAdapter(home).capabilities().subagents,
        )
        assertEquals(
            AgentCapability(CapabilityState.SUPPORTED),
            PiAdapter(home).capabilities().mcp,
        )
        assertEquals(
            AgentCapability(CapabilityState.UNSUPPORTED, CapabilityLimit.NOT_SUPPORTED),
            ZCodeAdapter(home).capabilities().subagents,
        )
        assertEquals(CapabilityState.SUPPORTED, KimiCodeAdapter(home).capabilities().skills.state)
    }
}
