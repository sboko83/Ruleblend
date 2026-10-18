package dev.ruleblend.app.library

import dev.ruleblend.app.place.placeId
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The subagent editor lists assistants that hold a copy even when the user tuned nothing for them,
 * so the usage scan has to say which assistant's file the copy is in, not only that a place has one.
 */
class LibraryUsageSubagentAgentsTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-usage-subagent-agents")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a subagent install names the assistants whose file holds it`() {
        val home = root.resolve("home")
        val claude = ClaudeCodeAdapter(home)
        val codex = CodexAdapter(home)
        listOf(".claude", ".codex").forEach { Files.createDirectories(home.resolve(it)) }
        val subagent = Block(id = "reviewer", name = "Reviewer", description = "Reviews", type = BlockType.SUBAGENT, content = "# Review\n")
        val subagents = SubagentInstallService(SubagentInstallStateStore(root))
        subagents.install(AgentGlobalTarget(claude), subagent)
        val repository = LibraryRepository(root.resolve("library")).also { it.init() }
        val scanner = LibraryUsageScanner(
            ConfigStore(root.resolve("config.json")),
            listOf(claude, codex),
            IntegrationService(blockResolver = repository::loadBlock),
            McpInstallService(installers = emptyList(), state = McpStateStore(root)),
            SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
            subagents,
        )

        val usage = LibraryUsageIndex.build(scanner.scan(listOf(subagent), emptyList()))

        val key = LibraryObjectKey(LibraryObjectKind.SUBAGENT, subagent.id)
        assertEquals(listOf(placeId(AgentGlobalTarget(claude))), usage.sitesOf(key).map { it.placeId })
        assertEquals(setOf(claude.id), usage.agentsOf(key))
    }
}
