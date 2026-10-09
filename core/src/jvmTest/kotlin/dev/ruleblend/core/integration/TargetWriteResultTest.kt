package dev.ruleblend.core.integration

import dev.ruleblend.core.blockFileReplacement
import dev.ruleblend.core.model.Block
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
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
import kotlin.test.assertTrue

/**
 * A target that spans two agents is written as one operation. The result says what became of each
 * file, and a failure halfway does not leave one agent holding a rule the other refused.
 */
class TargetWriteResultTest {

    private val blockedFiles = mutableListOf<Closeable>()
    private lateinit var dir: Path
    private lateinit var first: Path
    private lateinit var second: Path
    private val service = IntegrationService()
    private val block = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")

    /** A project read by two agents, each with its own file, and no pointer files in the way. */
    private fun target() = ProjectTarget(
        dir = dir,
        agents = listOf(agent("alpha", first), agent("beta", second)),
    )

    private fun agent(id: String, file: Path) = object : AgentAdapter {
        override val id = id
        override val name = id
        override fun isAvailable() = true
        override fun globalFile(): Path = dir.resolve("$id.md")
        override fun projectFile(projectDir: Path): Path = file
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-target-write")
        first = dir.resolve("first/AGENTS.md")
        second = dir.resolve("second/AGENTS.md")
        first.parent.createDirectories()
        second.parent.createDirectories()
    }

    @AfterTest
    fun tearDown() {
        blockedFiles.asReversed().forEach(Closeable::close)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a write that reaches every file reports each of them`() {
        val result = service.install(target(), block)

        assertTrue(result.isComplete)
        assertEquals(listOf(first, second), result.writes.map { it.file })
        assertTrue(result.writes.all { it.outcome == TargetWriteOutcome.WRITTEN })
        assertEquals(null, result.problem())
    }

    @Test
    fun `installing what is already there is unchanged, not written again`() {
        service.install(target(), block)

        val result = service.install(target(), block)

        assertTrue(result.isComplete)
        assertTrue(result.writes.all { it.outcome == TargetWriteOutcome.UNCHANGED })
    }

    @Test
    fun `a file that cannot be written undoes the file that already took the change`() {
        blockedFiles += blockFileReplacement(second)

        val result = service.install(target(), block)

        assertFalse(result.isComplete)
        assertEquals(TargetWriteOutcome.ROLLED_BACK, result.writes.first { it.file == first }.outcome)
        assertEquals(TargetWriteOutcome.FAILED, result.writes.first { it.file == second }.outcome)
        assertFalse(first.exists(), "the first agent must not keep a rule the second one refused")
    }

    @Test
    fun `the failure names the file it stopped at and what was undone`() {
        blockedFiles += blockFileReplacement(second)

        val problem = service.install(target(), block).problem()

        assertTrue(problem is TargetWriteException)
        assertTrue(problem.message.orEmpty().contains(second.toString()), problem.message.orEmpty())
        assertTrue(problem.message.orEmpty().contains("undone"), problem.message.orEmpty())
    }

    @Test
    fun `an earlier file keeps the text it had before the failed write`() {
        first.writeText("# Notes\n\nHand written.\n")
        blockedFiles += blockFileReplacement(second)

        service.install(target(), block)

        assertEquals("# Notes\n\nHand written.\n", first.readText(), "the file goes back exactly as it was")
    }

    @Test
    fun `an orphan removal that cannot finish raises instead of reporting the copy gone`() {
        service.install(target(), block)
        blockedFiles += blockFileReplacement(second)

        assertFailsWith<TargetWriteException> { service.removeOrphan(target(), block.id) }
        assertTrue(first.readText().contains(block.content), "the first file keeps its copy")
    }

    @Test
    fun `removing across a target reports per file too`() {
        service.install(target(), block)

        val result = service.remove(target(), block.id)

        assertTrue(result.isComplete)
        assertTrue(result.writes.all { it.outcome == TargetWriteOutcome.WRITTEN })
        assertFalse(first.readText().contains(block.id))
    }

    @Test
    fun `a caller that throws on the problem sees the same result inside it`() {
        blockedFiles += blockFileReplacement(second)

        val thrown = assertFailsWith<TargetWriteException> {
            service.install(target(), block).problem()?.let { throw it }
        }

        assertEquals(TargetWriteOutcome.FAILED, thrown.result.failed.single().outcome)
    }

    @Test
    fun `a pointer that cannot be written undoes the rule it would point at`() {
        val pointer = dir.resolve("pointer/CLAUDE.md")
        pointer.parent.createDirectories()
        val redirected = object : AgentAdapter by agent("alpha", first) {
            override fun projectRedirectFile(projectDir: Path): Path = pointer
        }
        blockFileReplacement(pointer).use {
            val result = service.install(ProjectTarget(dir = dir, agents = listOf(redirected)), block)

            assertFalse(result.isComplete)
            assertEquals(TargetWriteOutcome.ROLLED_BACK, result.writes.first { it.file == first }.outcome)
            assertEquals(TargetWriteOutcome.FAILED, result.writes.first { it.file == pointer }.outcome)
            assertFalse(first.exists(), "the rule must not stay behind without its pointer")
            val message = result.problem()?.message.orEmpty()
            assertTrue(message.contains(pointer.toString()) && message.contains("undone"), message)
        }
    }
}
