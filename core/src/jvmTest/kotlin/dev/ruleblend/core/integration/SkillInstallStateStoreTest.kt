package dev.ruleblend.core.integration

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
import kotlin.test.assertNull

class SkillInstallStateStoreTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-skill-state")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a record is found by its key and dropped by it`() {
        val store = SkillInstallStateStore(dir)
        store.record(SkillInstallRecord("agent", "claude-code", "review", "fingerprint"))

        assertEquals("fingerprint", store.find("agent", "claude-code", "review")?.fingerprint)
        store.remove("agent", "claude-code", "review")
        assertNull(store.find("agent", "claude-code", "review"))
    }

    @Test
    fun `origin round trips and legacy records read as base`() {
        val store = SkillInstallStateStore(dir)
        val profiled = SkillInstallRecord(
            "agent", "claude-code", "review", "fingerprint", origin = "p=testing",
            executableFiles = listOf("scripts/check.sh"),
        )
        store.record(profiled)

        assertEquals(profiled, SkillInstallStateStore(dir).find("agent", "claude-code", "review"))

        dir.resolve("skill-state.json").writeText(
            """[{"targetKey":"agent","agentId":"claude-code","skillId":"review","fingerprint":"fingerprint"}]""",
        )
        assertNull(SkillInstallStateStore(dir).find("agent", "claude-code", "review")?.origin)
        assertNull(SkillInstallStateStore(dir).find("agent", "claude-code", "review")?.executableFiles)
    }

    @Test
    fun `separate roots of one agent retain independent records`() {
        val store = SkillInstallStateStore(dir)
        store.record(SkillInstallRecord("agent", "zcode", "review", "native", "native"))
        store.record(SkillInstallRecord("agent", "zcode", "review", "shared", "shared"))

        assertEquals("native", store.find("agent", "zcode", "review", "native")?.fingerprint)
        assertEquals("shared", store.find("agent", "zcode", "review", "shared")?.fingerprint)
        store.remove("agent", "zcode", "review", "native")
        assertNull(store.find("agent", "zcode", "review", "native"))
        assertEquals("shared", store.find("agent", "zcode", "review", "shared")?.fingerprint)
    }

    /** One state file holds every record, so two agents recorded at once share a read-modify-write. */
    @Test
    fun `records written at the same time are all kept`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock")))
        val store = SkillInstallStateStore(dir, coordinator)
        val agents = listOf("claude-code", "codex").associateWith { agent -> (1..20).map { "$agent-skill-$it" } }
        val start = CyclicBarrier(agents.size)
        agents.map { (agent, skills) ->
            thread {
                start.await()
                skills.forEach { skill -> store.record(SkillInstallRecord("agent", agent, skill, "fingerprint")) }
            }
        }.forEach { it.join() }

        assertEquals(agents.values.flatten().size, store.all().size, "no record may be lost")
    }
}
