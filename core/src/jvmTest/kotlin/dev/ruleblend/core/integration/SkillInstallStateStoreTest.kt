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
import kotlin.test.assertTrue

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
    fun `copies in the retired shared root read as legacy records`() {
        dir.resolve("skill-state.json").writeText(
            """[
              {"targetKey":"agent","agentId":"claude-code","skillId":"review","fingerprint":"claude"},
              {"targetKey":"agent","agentId":"pi","skillId":"review","fingerprint":"pi"},
              {"targetKey":"agent","agentId":"zcode","skillId":"review","fingerprint":"native","directoryId":"native"},
              {"targetKey":"agent","agentId":"zcode","skillId":"review","fingerprint":"shared","directoryId":"shared"}
            ]""",
        )
        val store = SkillInstallStateStore(dir)

        assertEquals("claude", store.find("agent", "claude-code", "review")?.fingerprint)
        assertEquals("native", store.find("agent", "zcode", "review")?.fingerprint)
        assertNull(store.find("agent", "pi", "review"))
        assertEquals(setOf("pi" to "pi", "zcode" to "shared"), store.legacy().map { it.agentId to it.fingerprint }.toSet())

        store.remove("agent", "zcode", "review")
        assertNull(store.find("agent", "zcode", "review"))
        assertEquals(2, store.legacy().size)
        store.removeLegacy(store.legacy().first { it.agentId == "zcode" })
        assertEquals(listOf("pi"), store.legacy().map { it.agentId })
    }

    @Test
    fun `a new record always describes the native root`() {
        val store = SkillInstallStateStore(dir)
        store.record(SkillInstallRecord("agent", "pi", "review", "fp", directoryId = "shared"))

        assertEquals(NATIVE_SKILL_ROOT, store.find("agent", "pi", "review")?.directoryId)
        assertTrue(store.legacy().isEmpty())
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
