package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetMutationCoordinatorTest {

    private lateinit var dir: Path
    private lateinit var target: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-coordinator")
        target = dir.resolve("AGENTS.md")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    /** A read-modify-write cycle wide enough that an unsynchronized second writer must lose it. */
    private fun append(line: String) {
        val current = if (target.exists()) target.readText() else ""
        Thread.sleep(150)
        AtomicWrite.write(target, current + line + "\n")
    }

    private fun bothAppend(run: (String, () -> Unit) -> Unit) {
        val start = CyclicBarrier(2)
        val threads = listOf("first", "second").map { line ->
            thread {
                start.await()
                run(line) { append(line) }
            }
        }
        threads.forEach { it.join() }
    }

    @Test
    fun `without a lock the coordinator only runs the body`() {
        val coordinator = TargetMutationCoordinator()
        assertEquals("done", coordinator.mutate(target) { "done" })
    }

    @Test
    fun `nested mutations re-enter the same lock`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock"), timeoutMs = 1_000))
        val result = coordinator.mutate(target) { coordinator.mutate(target) { "nested" } }
        assertEquals("nested", result)
    }

    @Test
    fun `concurrent mutations of one file keep both writes`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock")))
        bothAppend { _, body -> coordinator.mutate(target, body = body) }
        assertEquals(setOf("first", "second"), target.readText().trim().lines().toSet())
    }

    /** The failure the coordinator exists to prevent: unsynchronized, the later rename wins alone. */
    @Test
    fun `unsynchronized mutations of one file lose a write`() {
        bothAppend { _, body -> body() }
        assertEquals(1, target.readText().trim().lines().size)
    }

    /** Stands in for an editor saving the file while Ruleblend is between its read and its rename. */
    private fun externalWrite(text: String) = AtomicWrite.write(target, text)

    @Test
    fun `rewrite recomputes over an external edit instead of dropping it`() {
        val coordinator = TargetMutationCoordinator()
        externalWrite("hand-written\n")
        var editing = true
        val written = coordinator.rewrite(target) { text ->
            if (editing) {
                editing = false
                externalWrite(text + "added by hand\n")
            }
            text + "installed by Ruleblend\n"
        }
        assertEquals("hand-written\nadded by hand\ninstalled by Ruleblend\n", written)
        assertEquals(written, target.readText())
    }

    @Test
    fun `rewrite refuses rather than overwrite a file that keeps changing`() {
        val coordinator = TargetMutationCoordinator()
        externalWrite("first\n")
        var edits = 0
        assertFailsWith<IllegalStateException> {
            coordinator.rewrite(target) { text ->
                externalWrite("edit ${edits++}\n")
                text + "installed by Ruleblend\n"
            }
        }
        assertEquals("edit ${edits - 1}\n", target.readText())
    }

    @Test
    fun `rewrite leaves the file alone when the transform asks for no change`() {
        val coordinator = TargetMutationCoordinator()
        externalWrite("hand-written\n")
        assertNull(coordinator.rewrite(target) { null })
        assertEquals("hand-written\n", target.readText())
    }

    @Test
    fun `rewrite skips an absent file unless it is given a starting text`() {
        val coordinator = TargetMutationCoordinator()
        assertNull(coordinator.rewrite(target) { "installed\n" })
        assertFalse(target.exists())
        assertEquals("installed\n", coordinator.rewrite(target, missing = "") { it + "installed\n" })
        assertEquals("installed\n", target.readText())
    }

    @Test
    fun `a guarded write is refused when the file was replaced after it was read`() {
        externalWrite("read this\n")
        val identity = FileIdentity.of(target)
        externalWrite("someone else wrote this\n")
        assertFalse(AtomicWrite.writeIfUnchanged(target, "Ruleblend wrote this\n", identity))
        assertEquals("someone else wrote this\n", target.readText())
    }

    @Test
    fun `a guarded write of a new file is refused when the file appeared meanwhile`() {
        assertNull(FileIdentity.of(target))
        externalWrite("appeared\n")
        assertFalse(AtomicWrite.writeIfUnchanged(target, "Ruleblend wrote this\n", expected = null))
        assertEquals("appeared\n", target.readText())
    }

    @Test
    fun `identical content is not drift, whatever the timestamp says`() {
        externalWrite("same\n")
        val identity = FileIdentity.of(target)
        Files.setLastModifiedTime(target, FileTime.fromMillis(0))
        externalWrite("same\n")
        assertTrue(AtomicWrite.writeIfUnchanged(target, "Ruleblend wrote this\n", identity))
        assertEquals("Ruleblend wrote this\n", target.readText())
    }
}
