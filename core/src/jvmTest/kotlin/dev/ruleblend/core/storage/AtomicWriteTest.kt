package dev.ruleblend.core.storage

import dev.ruleblend.core.blockFileReplacement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtomicWriteTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-atomic-write")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun posix(): Boolean = dir.fileSystem.supportedFileAttributeViews().contains("posix")

    @Test
    fun `a blocked replacement keeps the original and can succeed after release`() {
        val file = dir.resolve("locked.md")
        file.writeText("original")

        blockFileReplacement(file).use {
            assertFailsWith<IOException> { AtomicWrite.write(file, "replacement") }
            assertEquals("original", file.readText())
            assertEquals(listOf(file), dir.listDirectoryEntries(), "failed writes leave no temp files")
        }

        AtomicWrite.write(file, "replacement")
        assertEquals("replacement", file.readText())
        assertEquals(listOf(file), dir.listDirectoryEntries())
    }

    @Test
    fun `a new file gets the mode a plain create would give it, not owner-only`() {
        val reference = Files.createFile(dir.resolve("reference"))
        val target = dir.resolve("CLAUDE.md")

        AtomicWrite.write(target, "# Rules\n")

        assertEquals("# Rules\n", target.readText())
        assertEquals(Files.isReadable(reference), Files.isReadable(target))
        assertEquals(Files.isWritable(reference), Files.isWritable(target))
        if (posix()) {
            assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(target))
        }
    }

    @Test
    fun `rewriting keeps the mode the file already had`() {
        val script = dir.resolve("run.sh")
        script.writeText("#!/bin/sh\n")
        val mode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        if (posix()) Files.setPosixFilePermissions(script, mode)
        val executable = Files.isExecutable(script)

        AtomicWrite.write(script, "#!/bin/sh\necho hi\n")

        assertEquals("#!/bin/sh\necho hi\n", script.readText())
        assertEquals(executable, Files.isExecutable(script))
        if (posix()) assertEquals(mode, Files.getPosixFilePermissions(script))
    }

    @Test
    fun `no temp file is left beside the target`() {
        AtomicWrite.write(dir.resolve("a.md"), "x")
        AtomicWrite.writeIfUnchanged(dir.resolve("a.md"), "y", expected = null)

        assertEquals(listOf("a.md"), dir.listDirectoryEntries().map { it.fileName.toString() })
        assertEquals("x", dir.resolve("a.md").readText(), "the guarded write was refused")
    }

    @Test
    fun `a guarded write lands only while the file is what it was`() {
        val file = dir.resolve("a.md")
        AtomicWrite.write(file, "x")
        val identity = FileIdentity.of(file)

        assertTrue(AtomicWrite.writeIfUnchanged(file, "y", identity))
        assertFalse(AtomicWrite.writeIfUnchanged(file, "z", identity))
        assertEquals("y", file.readText())
    }

    @Test
    fun `concurrent readers see only complete old or new content`() {
        val file = dir.resolve("shared.md")
        val old = "a".repeat(32_768)
        val new = "b".repeat(32_768)
        AtomicWrite.write(file, old)
        val running = AtomicBoolean(true)
        val reads = AtomicInteger()
        val readerFailure = AtomicReference<Throwable?>()
        val observed = mutableListOf<String>()
        val reader = Thread {
            try {
                while (running.get()) {
                    val value = Files.readString(file)
                    reads.incrementAndGet()
                    if (value != old && value != new) synchronized(observed) { observed.add(value) }
                    Thread.sleep(1)
                }
            } catch (error: Throwable) {
                readerFailure.set(error)
            }
        }.apply { start() }
        try {
            repeat(40) { AtomicWrite.write(file, if (it % 2 == 0) new else old) }
        } finally {
            running.set(false)
            reader.join()
        }
        assertNull(readerFailure.get(), "reader failed during an atomic replacement")
        assertTrue(reads.get() > 0, "reader did not run alongside writes")
        assertTrue(observed.isEmpty(), "reader saw an incomplete replacement")
    }
}
