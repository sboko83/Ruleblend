package dev.ruleblend.mcp.install

import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpStateStoreTest {

    private lateinit var dir: Path
    private lateinit var store: McpStateStore

    private val record = McpInstallRecord(
        targetKey = "agent",
        agentId = "claude-code",
        blockId = "context7",
        version = 1,
        config = McpServerConfig.Stdio(command = "npx"),
    )

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-mcp-state")
        store = McpStateStore(dir)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    /** One state file holds every record, so two agents recorded at once share a read-modify-write. */
    @Test
    fun `records written at the same time are all kept`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock")))
        val shared = McpStateStore(dir, coordinator)
        val blocks = listOf("first", "second").associateWith { id -> (1..20).map { "$id-$it" } }
        val start = CyclicBarrier(blocks.size)
        blocks.map { (agent, ids) ->
            thread {
                start.await()
                ids.forEach { id -> shared.record(record.copy(agentId = agent, blockId = id)) }
            }
        }.forEach { it.join() }

        assertEquals(blocks.values.flatten().size, shared.all().size, "no record may be lost")
    }

    @Test
    fun `missing file means no records`() {
        assertEquals(emptyList(), store.all())
        assertNull(store.find("agent", "claude-code", "context7"))
    }

    @Test
    fun `record round trips`() {
        val profiled = record.copy(origin = "p=testing")
        store.record(profiled)
        assertEquals(profiled, McpStateStore(dir).find("agent", "claude-code", "context7"))
    }

    @Test
    fun `legacy record without origin reads as base`() {
        dir.resolve("mcp-state.json").writeText(
            """[{"targetKey":"agent","agentId":"claude-code","blockId":"context7","version":1,"config":{"transport":"stdio","command":"npx","args":[],"env":{}}}]""",
        )

        assertNull(store.find("agent", "claude-code", "context7")?.origin)
    }

    @Test
    fun `record replaces the same key`() {
        store.record(record)
        store.record(record.copy(version = 2))
        assertEquals(1, store.all().size)
        assertEquals(2, store.find("agent", "claude-code", "context7")?.version)
    }

    @Test
    fun `remove drops every agent's record for the block in the target`() {
        store.record(record)
        store.record(record.copy(agentId = "codex"))
        val projectKey = "project:${dir.resolve("project").projectKey()}"
        store.record(record.copy(targetKey = projectKey))
        store.remove("agent", "context7")
        assertEquals(listOf(projectKey), store.all().map { it.targetKey })
    }

    @Test
    fun `corrupt file degrades to no records`() {
        dir.resolve("mcp-state.json").writeText("not json")
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `a record written under an unnormalized project path is found under its one key`() {
        val project = dir.resolve("ledger")
        val roundabout = dir.resolve("ledger/../ledger")
        store.record(record.copy(targetKey = "project:$roundabout"))

        val target = ProjectTarget(project, emptyList())

        assertNotNull(
            store.find(targetKey(target), record.agentId, record.blockId),
            "the same checkout must not lose its install record to a second spelling of its path",
        )
    }

    @Test
    fun `the key a target asks with is the normalized one`() {
        val roundabout = ProjectTarget(dir.resolve("ledger/../ledger"), emptyList())
        val plain = ProjectTarget(dir.resolve("ledger"), emptyList())

        assertEquals(targetKey(plain), targetKey(roundabout))
    }
}
