package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileReadScopeTest {

    private val dir: Path = Files.createTempDirectory("read-scope").also { it.createDirectories() }
    private val file: Path = dir.resolve("AGENTS.md").also { it.writeText("one\n") }

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a file asked about many times inside a scope is opened once`() {
        FileReadScope.reading {
            repeat(20) { assertEquals("one\n", FileReadScope.text(file)) }
            assertEquals(1, FileReadScope.reads)
        }
    }

    @Test
    fun `outside a scope every read goes to disk`() {
        assertEquals("one\n", FileReadScope.text(file))
        file.writeText("two\n")
        assertEquals("two\n", FileReadScope.text(file))
        assertEquals(0, FileReadScope.reads)
    }

    @Test
    fun `a scope answers from the file as it was when it opened`() {
        FileReadScope.reading {
            assertEquals("one\n", FileReadScope.text(file))
            // A scope is read-only and short-lived on purpose: this is what it promises to hold.
            file.writeText("changed under it\n")
            assertEquals("one\n", FileReadScope.text(file))
        }
        assertEquals("changed under it\n", FileReadScope.text(file))
    }

    @Test
    fun `an absent file is remembered as absent instead of being stat-ed again`() {
        val missing = dir.resolve("nothing.md")
        FileReadScope.reading {
            assertNull(FileReadScope.textOrNull(missing))
            assertNull(FileReadScope.textOrNull(missing))
            assertTrue(!FileReadScope.exists(missing))
            assertEquals(1, FileReadScope.reads)
        }
    }

    @Test
    fun `a parsed answer is computed once per scope`() {
        var parses = 0
        FileReadScope.reading {
            repeat(5) {
                FileReadScope.cached(file to "parsed") {
                    parses++
                    FileReadScope.text(file).trim()
                }
            }
        }
        assertEquals(1, parses)
        // No scope, no memo: the next caller gets a fresh answer.
        repeat(3) {
            FileReadScope.cached(file to "parsed") { (++parses).toString() }
        }
        assertEquals(4, parses)
    }

    @Test
    fun `nested scopes share the outer one instead of re-reading`() {
        FileReadScope.reading {
            FileReadScope.text(file)
            FileReadScope.reading { FileReadScope.text(file) }
            assertEquals(1, FileReadScope.reads)
        }
    }

    @Test
    fun `scopes on two threads do not see each other's reads`() {
        val other = Thread {
            FileReadScope.reading {
                FileReadScope.text(file)
                assertEquals(1, FileReadScope.reads)
            }
        }
        FileReadScope.reading {
            FileReadScope.text(file)
            other.start()
            other.join()
            assertEquals(1, FileReadScope.reads)
        }
    }

    @Test
    fun `a scope leaves nothing behind for the next one on the same thread`() {
        FileReadScope.reading { FileReadScope.text(file) }
        file.writeText("after\n")
        FileReadScope.reading {
            assertEquals("after\n", FileReadScope.text(file))
            assertEquals(1, FileReadScope.reads)
        }
        assertEquals("after\n", file.readText())
    }
}
