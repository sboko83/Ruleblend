package dev.ruleblend.core.integration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentInstallStateStoreTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-subagent-state")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `origin round trips and legacy records read as base`() {
        val store = SubagentInstallStateStore(dir)
        val profiled = SubagentInstallRecord("agent", "codex", "reviewer", "fingerprint", origin = "p=testing")
        store.record(profiled)

        assertEquals(profiled, SubagentInstallStateStore(dir).find("agent", "codex", "reviewer"))

        dir.resolve("subagent-state.json").writeText(
            """[{"targetKey":"agent","agentId":"codex","subagentId":"reviewer","fingerprint":"fingerprint"}]""",
        )
        assertNull(SubagentInstallStateStore(dir).find("agent", "codex", "reviewer")?.origin)
        assertNull(SubagentInstallStateStore(dir).find("agent", "codex", "reviewer")?.fileName)
    }
}
