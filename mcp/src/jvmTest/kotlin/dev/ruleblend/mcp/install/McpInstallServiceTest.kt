package dev.ruleblend.mcp.install

import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.KimiCodeAdapter
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.integration.ZCodeAdapter
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.McpStatus
import dev.ruleblend.core.integration.McpWrite
import dev.ruleblend.core.integration.OrphanRemoval
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpInstallServiceTest {

    private lateinit var home: Path
    private lateinit var project: Path
    private lateinit var service: McpInstallService

    private val stdioBlock = mcpBlock("context7", McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7")))
    private val httpBlock = mcpBlock("remote", McpServerConfig.Http(url = "https://mcp.example.com/mcp"))

    private val globalClaude get() = AgentGlobalTarget(ClaudeCodeAdapter(home))
    private val globalCodex get() = AgentGlobalTarget(CodexAdapter(home))
    private val globalKimi get() = AgentGlobalTarget(KimiCodeAdapter(home))
    private val kimiProjectTarget get() = ProjectTarget(project, listOf(KimiCodeAdapter(home)))
    private val globalZCode get() = AgentGlobalTarget(ZCodeAdapter(home))
    private val zCodeProjectTarget get() = ProjectTarget(project, listOf(ZCodeAdapter(home)))
    private val projectTarget get() = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), CodexAdapter(home)))

    private fun mcpBlock(id: String, config: McpServerConfig, version: Int = 1) = Block(
        id = id,
        name = id,
        version = version,
        type = BlockType.MCP,
        content = McpConfigCodec.serialize(config),
    )

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-mcp-home")
        project = Files.createTempDirectory("ruleblend-mcp-project")
        home.resolve(".claude").createDirectories()
        home.resolve(".codex").createDirectories()
        home.resolve(".kimi-code").createDirectories()
        home.resolve(".zcode").createDirectories()
        service = McpInstallService(
            installers = listOf(ClaudeCodeMcpInstaller(home), CodexMcpInstaller(home), KimiCodeMcpInstaller(home), ZCodeMcpInstaller(home), PiMcpInstaller(home)),
            state = McpStateStore(home.resolve(".ruleblend")),
        )
    }

    /**
     * An entry and the record describing it are one change: installed in parallel, every server must
     * end up both in the config and in the state, or its status could not read as installed.
     */
    @Test
    fun `servers installed in parallel are each recorded with their entry`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(home.resolve("ruleblend.lock")))
        val shared = McpInstallService(
            installers = listOf(ClaudeCodeMcpInstaller(home, coordinator), CodexMcpInstaller(home, coordinator)),
            state = McpStateStore(home.resolve(".ruleblend"), coordinator),
            coordinator = coordinator,
        )
        val blocks = (1..4).map { mcpBlock("server-$it", McpServerConfig.Stdio(command = "npx", args = listOf("-y", "s$it"))) }
        val start = CyclicBarrier(blocks.size)
        blocks.map { block -> thread { start.await(); shared.install(globalClaude, block) } }.forEach { it.join() }

        assertEquals(
            blocks.associate { it.id to InstallStatus.SYNCED },
            blocks.associate { it.id to shared.status(globalClaude, it)?.status },
        )
    }

    @Test
    fun `removing a foreign server keeps neighboring config entries`() {
        val file = home.resolve(".claude.json")
        file.writeText(
            """{"mcpServers":{"remove-me":{"type":"stdio","command":"npx"},"keep-me":{"type":"stdio","command":"node"}}}""",
        )
        val entry = service.entries(globalClaude).single { it.name == "remove-me" }

        service.removeForeign(globalClaude, entry)

        assertEquals(listOf("keep-me"), service.entries(globalClaude).map { it.name })
        assertTrue(file.readText().contains("keep-me"))
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `install to agent global lands in the agent config and is synced`() {
        service.install(globalClaude, stdioBlock)

        assertTrue("context7" in home.resolve(".claude.json").readText())
        assertFalse(home.resolve(".codex/config.toml").exists())
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalClaude, stdioBlock))
        assertNull(service.status(globalCodex, stdioBlock))
    }

    @Test
    fun `install to project writes both agent files`() {
        service.install(projectTarget, stdioBlock)

        assertTrue("context7" in project.resolve(".mcp.json").readText())
        assertTrue("[mcp_servers.context7]" in project.resolve(".codex/config.toml").readText())
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(projectTarget, stdioBlock))
    }

    @Test
    fun `http block installs into both agent configs and reads as synced`() {
        service.install(projectTarget, httpBlock)

        assertTrue("remote" in project.resolve(".mcp.json").readText())
        val codexConfig = project.resolve(".codex/config.toml").readText()
        assertTrue("[mcp_servers.remote]" in codexConfig)
        assertTrue("url = \"https://mcp.example.com/mcp\"" in codexConfig)
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(projectTarget, httpBlock))
        assertEquals(emptyList(), service.unsupportedFor(projectTarget, httpBlock))
    }

    @Test
    fun `Kimi installs global and project native mcp json without a type field`() {
        service.install(globalKimi, stdioBlock)
        service.install(kimiProjectTarget, httpBlock)

        val global = home.resolve(".kimi-code/mcp.json").readText()
        val local = project.resolve(".kimi-code/mcp.json").readText()
        assertTrue("context7" in global)
        assertTrue("remote" in local)
        assertFalse("\"type\"" in global)
        assertFalse(project.resolve(".mcp.json").exists())
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalKimi, stdioBlock))
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(kimiProjectTarget, httpBlock))
    }

    @Test
    fun `ZCode installs global shared and project compatibility MCP`() {
        service.install(globalZCode, stdioBlock)
        service.install(zCodeProjectTarget, httpBlock)

        val global = home.resolve(".agents/mcp.json").readText()
        val projectConfig = project.resolve(".mcp.json").readText()
        assertTrue("context7" in global)
        assertTrue("remote" in projectConfig)
        assertTrue("\"type\": \"stdio\"" in global)
        assertTrue("\"type\": \"http\"" in projectConfig)
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalZCode, stdioBlock))
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(zCodeProjectTarget, httpBlock))
    }

    @Test
    fun `Pi installs into native global and project files and requires project trust`() {
        val globalPi = AgentGlobalTarget(PiAdapter(home))
        val piProject = ProjectTarget(project, listOf(PiAdapter(home)))
        service.install(globalPi, stdioBlock)
        service.install(piProject, httpBlock)

        assertTrue("context7" in home.resolve(".pi/agent/mcp.json").readText())
        assertTrue("remote" in project.resolve(".pi/mcp.json").readText())
        assertFalse(home.resolve(".agents/mcp.json").exists())
        assertFalse(project.resolve(".mcp.json").exists())
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalPi, stdioBlock))
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(piProject, httpBlock))
        assertEquals(listOf(project.resolve(".pi/mcp.json")), service.configFiles(piProject))
        assertEquals(listOf("pi"), service.agentsRequiringTrust(piProject))

        service.remove(piProject, httpBlock)
        assertFalse("remote" in project.resolve(".pi/mcp.json").readText())
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalPi, stdioBlock))
    }

    @Test
    fun `hand-edited entry reads as modified`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))

        assertEquals(McpStatus(InstallStatus.MODIFIED, conflict = false), service.status(globalClaude, stdioBlock))
    }

    @Test
    fun `library edit after install reads as update available`() {
        service.install(globalClaude, stdioBlock)
        val edited = mcpBlock("context7", McpServerConfig.Stdio(command = "bunx"), version = 2)

        assertEquals(McpStatus(InstallStatus.UPDATE_AVAILABLE, conflict = false), service.status(globalClaude, edited))

        service.install(globalClaude, edited)
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalClaude, edited))
    }

    @Test
    fun `partial install across a project's agents is update available`() {
        service.install(projectTarget, stdioBlock)
        val codexOnly = CodexMcpInstaller(home)
        codexOnly.remove(projectTarget, stdioBlock.id)

        assertEquals(
            McpStatus(InstallStatus.UPDATE_AVAILABLE, conflict = false),
            service.status(projectTarget, stdioBlock),
        )
    }

    @Test
    fun `remove cleans configs and state`() {
        service.install(projectTarget, stdioBlock)
        service.remove(projectTarget, stdioBlock)

        assertNull(service.status(projectTarget, stdioBlock))
        assertFalse("context7" in project.resolve(".mcp.json").readText())
        assertFalse("context7" in project.resolve(".codex/config.toml").readText())
        assertEquals(emptyList(), McpStateStore(home.resolve(".ruleblend")).all())
    }

    @Test
    fun `releasing keeps the config entry and leaves it foreign until taken back`() {
        service.install(globalClaude, stdioBlock)
        val config = home.resolve(".claude.json")
        val text = config.readText()

        service.release(globalClaude, stdioBlock.id)

        assertEquals(text, config.readText())
        assertEquals(emptyList(), McpStateStore(home.resolve(".ruleblend")).all())
        assertEquals(McpStatus(InstallStatus.MODIFIED, conflict = true), service.status(globalClaude, stdioBlock))
        assertEquals(McpWrite.FOREIGN, service.remove(globalClaude, stdioBlock))
        assertEquals(text, config.readText())

        service.takeOwnership(globalClaude, service.entries(globalClaude).single { it.name == "context7" }, stdioBlock)
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalClaude, stdioBlock))
    }

    @Test
    fun `pre-existing foreign entry with our name is a conflict`() {
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"context7":{"type":"stdio","command":"/usr/local/bin/theirs"}}}""",
        )

        val status = service.status(globalClaude, stdioBlock)
        assertEquals(McpStatus(InstallStatus.MODIFIED, conflict = true), status)
    }

    @Test
    fun `reserved name and invalid config are refused`() {
        val reserved = mcpBlock("ruleblend", McpServerConfig.Stdio(command = "x"))
        val unparsable = stdioBlock.copy(content = "not json")
        val blank = mcpBlock("blank", McpServerConfig.Stdio(command = " "))

        // The safe entry point reports them: one bad member must not abort a whole group install.
        assertEquals(McpWrite.NOT_APPLICABLE, service.install(globalClaude, reserved))
        assertEquals(McpWrite.NOT_APPLICABLE, service.install(globalClaude, unparsable))
        assertEquals(McpWrite.NOT_APPLICABLE, service.install(globalClaude, blank))
        assertFalse(home.resolve(".claude.json").exists())

        // Forcing one is a programming error, not a user decision the UI can offer.
        assertFailsWith<IllegalArgumentException> { service.forceInstall(globalClaude, reserved) }
        assertFailsWith<IllegalArgumentException> { service.forceInstall(globalClaude, unparsable) }
        assertFailsWith<IllegalArgumentException> { service.forceInstall(globalClaude, blank) }
    }

    @Test
    fun `targets without installers do not apply`() {
        val bare = ProjectTarget(project, emptyList())
        assertFalse(service.appliesTo(bare))
        assertTrue(service.appliesTo(projectTarget))
    }

    @Test
    fun `entries list top-level foreign configs and retain unsupported text`() {
        home.resolve(".claude.json").writeText(
            """{"mcpServers":{"known":{"type":"stdio","command":"npx"},"unknown":{"type":"ws","url":"wss://mcp.example.com"}},"projects":{"/repo":{"mcpServers":{"project-only":{"command":"nope"}}}}}""",
        )

        val entries = service.entries(globalClaude).associateBy { it.name }

        assertEquals(setOf("known", "unknown"), entries.keys)
        assertEquals(McpServerConfig.Stdio(command = "npx"), entries.getValue("known").config)
        assertNull(entries.getValue("unknown").config)
        assertTrue("\"type\": \"ws\"" in entries.getValue("unknown").text)
        assertEquals(home.resolve(".claude.json"), entries.getValue("unknown").file)
    }

    @Test
    fun `malformed JSON and TOML do not stop entry enumeration`() {
        home.resolve(".claude.json").writeText("{ broken")
        home.resolve(".codex/config.toml").writeText("[mcp_servers.broken")

        assertEquals(emptyList(), service.entries(globalClaude))
        assertEquals(emptyList(), service.entries(globalCodex))
    }

    @Test
    fun `classified entries keep each config address separate`() {
        service.install(projectTarget, stdioBlock)

        val entries = service.classifiedEntries(projectTarget, setOf(stdioBlock.id))

        assertEquals(
            setOf(project.resolve(".mcp.json"), project.resolve(".codex/config.toml")),
            entries.map { it.value.file }.toSet(),
        )
        assertEquals(setOf(PlaceEntryOrigin.MANAGED), entries.map { it.origin }.toSet())
        assertEquals(2, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `foreign entry takes ownership without rewriting its config`() {
        val file = home.resolve(".claude.json")
        val text = """{"mcpServers":{"context7":{"type":"stdio","command":"npx","args":["-y","ctx7"]}}}"""
        file.writeText(text)
        val entry = service.entries(globalClaude).single()
        val renamed = mcpBlock("context7-2", McpConfigCodec.parse(stdioBlock.content).getOrThrow())

        service.takeOwnership(globalClaude, entry, renamed)

        assertEquals(text, file.readText())
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalClaude, renamed))
        assertEquals(
            PlaceEntryOrigin.MANAGED,
            service.classifiedEntries(globalClaude, setOf(renamed.id)).single().origin,
        )
    }

    @Test
    fun `a renamed entry adopted from a shared config is owned by every agent reading it`() {
        val shared = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), ZCodeAdapter(home)))
        val file = project.resolve(".mcp.json")
        val text = """{"mcpServers":{"context7":{"type":"stdio","command":"npx","args":["-y","ctx7"]}}}"""
        file.writeText(text)
        val entry = service.entries(shared).single()
        val renamed = mcpBlock("context7-2", McpConfigCodec.parse(stdioBlock.content).getOrThrow())

        service.takeOwnership(shared, entry, renamed)

        assertEquals(text, file.readText())
        // Both agents read this one file; an entry kept under its own name must not read as missing
        // from the second of them just because the first is the one discovery listed it under.
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(shared, renamed))
    }

    // --- safe writes protect a hand-edited or foreign entry ---

    @Test
    fun `safe install keeps a hand-edited entry and reports it`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))

        assertEquals(McpWrite.MODIFIED, service.install(globalClaude, stdioBlock))
        assertTrue("bunx" in file.readText())
    }

    @Test
    fun `safe remove keeps a hand-edited entry and reports it`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))

        assertEquals(McpWrite.MODIFIED, service.remove(globalClaude, stdioBlock))
        assertTrue("context7" in file.readText())
    }

    @Test
    fun `safe writes keep a foreign entry with our name`() {
        val file = home.resolve(".claude.json")
        file.writeText("""{"mcpServers":{"context7":{"type":"stdio","command":"/usr/local/bin/theirs"}}}""")

        assertEquals(McpWrite.FOREIGN, service.install(globalClaude, stdioBlock))
        assertEquals(McpWrite.FOREIGN, service.remove(globalClaude, stdioBlock))
        assertTrue("/usr/local/bin/theirs" in file.readText())
    }

    @Test
    fun `an update available entry is ours, so a safe install takes it`() {
        service.install(globalClaude, stdioBlock)
        val edited = mcpBlock("context7", McpServerConfig.Stdio(command = "bunx"), version = 2)

        assertEquals(McpWrite.DONE, service.install(globalClaude, edited))
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalClaude, edited))
    }

    @Test
    fun `safe installs record and preserve group and profile origins`() {
        for (origin in listOf("g=review", "p=review")) {
            assertEquals(McpWrite.DONE, service.install(globalClaude, stdioBlock, origin))
            assertEquals(origin, service.installedOrigins(globalClaude)[stdioBlock.id])

            assertEquals(McpWrite.DONE, service.install(globalClaude, stdioBlock.copy(version = 2)))
            assertEquals(origin, service.installedOrigins(globalClaude)[stdioBlock.id])
        }
    }

    @Test
    fun `a hand edit in one agent config protects the whole project target`() {
        service.install(projectTarget, stdioBlock)
        val codexFile = project.resolve(".codex/config.toml")
        codexFile.writeText(codexFile.readText().replace("npx", "bunx"))

        assertEquals(McpWrite.MODIFIED, service.install(projectTarget, stdioBlock))
        assertEquals(McpWrite.MODIFIED, service.remove(projectTarget, stdioBlock))
        assertTrue("bunx" in codexFile.readText())
        assertTrue("context7" in project.resolve(".mcp.json").readText())
    }

    @Test
    fun `force writes over what the safe path refuses`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))

        service.forceInstall(globalClaude, stdioBlock)
        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(globalClaude, stdioBlock))

        file.writeText(file.readText().replace("npx", "bunx"))
        service.forceRemove(globalClaude, stdioBlock.id)
        assertNull(service.status(globalClaude, stdioBlock))
    }

    @Test
    fun `safe remove of an entry that is not there is not an outcome`() {
        assertEquals(McpWrite.NOT_APPLICABLE, service.remove(globalClaude, stdioBlock))
    }

    // --- taking a local edit into the library ---

    @Test
    fun `local change reads the single edited copy`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))

        val local = service.localChange(globalClaude, stdioBlock)

        assertTrue("bunx" in local)
        service.acceptLocalChange(globalClaude, stdioBlock.copy(content = local, version = 2), local)
        assertEquals(
            McpStatus(InstallStatus.SYNCED, conflict = false),
            service.status(globalClaude, stdioBlock.copy(content = local, version = 2)),
        )
    }

    @Test
    fun `two agents edited apart refuse to become one version`() {
        service.install(projectTarget, stdioBlock)
        val claudeFile = project.resolve(".mcp.json")
        val codexFile = project.resolve(".codex/config.toml")
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "/opt/wrapper"))

        assertFailsWith<IllegalArgumentException> { service.localChange(projectTarget, stdioBlock) }
        assertTrue("bunx" in claudeFile.readText())
        assertTrue("/opt/wrapper" in codexFile.readText())
    }

    @Test
    fun `one edited agent and one untouched is still a disagreement`() {
        service.install(projectTarget, stdioBlock)
        val claudeFile = project.resolve(".mcp.json")
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))

        assertFailsWith<IllegalArgumentException> { service.localChange(projectTarget, stdioBlock) }
        assertTrue("npx" in project.resolve(".codex/config.toml").readText())
    }

    @Test
    fun `agents edited the same way agree, and the version lands in both`() {
        service.install(projectTarget, stdioBlock)
        val claudeFile = project.resolve(".mcp.json")
        val codexFile = project.resolve(".codex/config.toml")
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "bunx"))

        val local = service.localChange(projectTarget, stdioBlock)
        val saved = stdioBlock.copy(content = local, version = 2)
        service.acceptLocalChange(projectTarget, saved, local)

        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(projectTarget, saved))
        assertTrue("bunx" in claudeFile.readText())
        assertTrue("bunx" in codexFile.readText())
    }

    @Test
    fun `an edit that lands between the read and the commit stops the save`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))
        val local = service.localChange(globalClaude, stdioBlock)

        file.writeText(file.readText().replace("bunx", "pnpx"))

        assertFailsWith<IllegalArgumentException> {
            service.acceptLocalChange(globalClaude, stdioBlock.copy(content = local, version = 2), local)
        }
        assertTrue("pnpx" in file.readText())
    }

    @Test
    fun `local change of an entry that is not installed is refused`() {
        assertFailsWith<IllegalArgumentException> { service.localChange(globalClaude, stdioBlock) }
    }

    @Test
    fun `naming an agent answers the disagreement and its copy becomes the version`() {
        service.install(projectTarget, stdioBlock)
        val claudeFile = project.resolve(".mcp.json")
        val codexFile = project.resolve(".codex/config.toml")
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "/opt/wrapper"))

        val local = service.localChange(projectTarget, stdioBlock, "codex")
        assertTrue("/opt/wrapper" in local)
        val saved = stdioBlock.copy(content = local, version = 2)
        service.acceptLocalChange(projectTarget, saved, local, "codex")

        assertEquals(McpStatus(InstallStatus.SYNCED, conflict = false), service.status(projectTarget, saved))
        assertTrue("/opt/wrapper" in claudeFile.readText())
        assertTrue("/opt/wrapper" in codexFile.readText())
    }

    @Test
    fun `an edit to the named copy between the read and the commit stops the save`() {
        service.install(projectTarget, stdioBlock)
        val claudeFile = project.resolve(".mcp.json")
        val codexFile = project.resolve(".codex/config.toml")
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))
        codexFile.writeText(codexFile.readText().replace("npx", "/opt/wrapper"))
        val local = service.localChange(projectTarget, stdioBlock, "claude-code")

        claudeFile.writeText(claudeFile.readText().replace("bunx", "pnpx"))

        assertFailsWith<IllegalArgumentException> {
            service.acceptLocalChange(projectTarget, stdioBlock.copy(content = local, version = 2), local, "claude-code")
        }
        assertTrue("pnpx" in claudeFile.readText())
        assertTrue("/opt/wrapper" in codexFile.readText())
    }

    @Test
    fun `an agent that does not hold the entry cannot be the source`() {
        service.install(globalClaude, stdioBlock)

        assertFailsWith<IllegalArgumentException> { service.localChange(globalClaude, stdioBlock, "codex") }
    }

    @Test
    fun `copies are read per agent, each with its own text`() {
        service.install(projectTarget, stdioBlock)
        val claudeFile = project.resolve(".mcp.json")
        claudeFile.writeText(claudeFile.readText().replace("npx", "bunx"))

        val copies = service.installedCopies(projectTarget, stdioBlock).associate { it.agentId to it.text }

        assertEquals(setOf("claude-code", "codex"), copies.keys)
        assertTrue("bunx" in copies.getValue("claude-code"))
        assertTrue("npx" in copies.getValue("codex"))
    }

    @Test
    fun `an update without an origin keeps the one the entry was installed with`() {
        service.forceInstall(globalClaude, stdioBlock, "g=bundle")
        service.forceInstall(globalClaude, stdioBlock)
        assertEquals("g=bundle", service.installedOrigins(globalClaude)[stdioBlock.id])
    }

    @Test
    fun `an orphaned entry goes when the library block it came from is deleted`() {
        service.install(globalClaude, stdioBlock)

        val outcome = service.removeOrphan(globalClaude, stdioBlock.id)

        assertEquals(OrphanRemoval.REMOVED, outcome)
        assertNull(service.status(globalClaude, stdioBlock))
        assertTrue(service.installedOrigins(globalClaude).isEmpty())
    }

    @Test
    fun `a hand-edited orphan entry stays and is reported`() {
        service.install(globalClaude, stdioBlock)
        val file = home.resolve(".claude.json")
        file.writeText(file.readText().replace("npx", "bunx"))

        val outcome = service.removeOrphan(globalClaude, stdioBlock.id)

        assertEquals(OrphanRemoval.PROTECTED, outcome)
        assertTrue(file.readText().contains("bunx"))
    }
}
