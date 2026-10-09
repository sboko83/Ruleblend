package dev.ruleblend.mcp

import dev.ruleblend.mcp.skill.BundledSkill
import dev.ruleblend.mcp.skill.ClaudeCodeSkillInstaller
import dev.ruleblend.mcp.skill.PiSkillInstaller
import dev.ruleblend.mcp.skill.SkillStatus
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

class BundledIntegrationTest {

    private lateinit var home: Path
    private val binary: Path get() = Path.of("/Applications/Ruleblend.app/Contents/MacOS/Ruleblend")

    private val claudeSkill: Path
        get() = home.resolve(".claude").resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md")

    private fun integration() = BundledIntegration(
        connectors = listOf(ClaudeCodeConnector(home)),
        skillInstallers = listOf(ClaudeCodeSkillInstaller(home), PiSkillInstaller(home)),
        binary = binary,
    )

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-integration-home")
        home.resolve(".claude").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `an agent that is not on this machine has no state`() {
        assertEquals(listOf("claude-code"), integration().states().map { it.agentId })
    }

    @Test
    fun `an assistant without an MCP surface still reports its skill`() {
        home.resolve(".pi").resolve("agent").createDirectories()

        val pi = integration().stateOf("pi")

        assertEquals(null, pi?.mcp)
        assertEquals(SkillStatus.NOT_INSTALLED, pi?.skill?.status)
    }

    @Test
    fun `a fresh install is current and needs nothing`() {
        val integration = integration()
        ClaudeCodeConnector(home).register(binary)
        ClaudeCodeSkillInstaller(home).install()

        val state = integration.stateOf("claude-code")

        assertEquals(McpRegistration.REGISTERED, state?.mcp?.registration)
        assertEquals(SkillStatus.INSTALLED, state?.skill?.status)
        assertEquals(false, state?.outdated)
        assertEquals(emptyList(), integration.updateAll())
    }

    @Test
    fun `updateAll rewrites an older skill and an unstamped entry`() {
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"ruleblend":{"type":"stdio","command":${kotlinx.serialization.json.JsonPrimitive(binary.toString())},"args":["--mcp"]}}}""",
        )
        claudeSkill.parent.createDirectories()
        claudeSkill.writeText("# Old\n\n<!-- ruleblend-managed skill v0 -->\n")
        val integration = integration()

        assertEquals(listOf("claude-code"), integration.updateAll())

        val state = integration.stateOf("claude-code")
        assertEquals(McpRegistration.REGISTERED, state?.mcp?.registration)
        assertEquals(MCP_ENTRY_VERSION, state?.mcp?.installed)
        assertEquals(SkillStatus.INSTALLED, state?.skill?.status)
        assertEquals(BundledSkill.version, state?.skill?.installed)
    }

    @Test
    fun `a foreign skill is reported and left exactly as the user wrote it`() {
        ClaudeCodeConnector(home).register(binary)
        claudeSkill.parent.createDirectories()
        claudeSkill.writeText("# Someone else's ruleblend skill\n")
        val integration = integration()

        val state = integration.stateOf("claude-code")
        assertTrue(state!!.blocked)
        assertEquals(false, state.outdated)

        integration.updateAll()

        assertEquals("# Someone else's ruleblend skill\n", claudeSkill.readText())
    }

    @Test
    fun `an assistant that was never connected is not reported as outdated`() {
        val state = integration().stateOf("claude-code")

        assertEquals(McpRegistration.NOT_REGISTERED, state?.mcp?.registration)
        assertEquals(SkillStatus.NOT_INSTALLED, state?.skill?.status)
        assertEquals(false, state?.outdated)
    }

    @Test
    fun `without a server binary there is nothing to say about the MCP entry`() {
        val state = BundledIntegration(
            connectors = listOf(ClaudeCodeConnector(home)),
            skillInstallers = listOf(ClaudeCodeSkillInstaller(home)),
            binary = null,
        ).stateOf("claude-code")

        assertEquals(null, state?.mcp)
        assertEquals(SkillStatus.NOT_INSTALLED, state?.skill?.status)
    }
}
