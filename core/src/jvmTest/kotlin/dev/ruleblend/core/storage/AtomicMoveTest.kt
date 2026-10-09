package dev.ruleblend.core.storage

import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AtomicMoveTest {
    @Test
    fun `a transient sharing violation is retried`() {
        val source = Path.of("source")
        val target = Path.of("target")
        var attempts = 0

        AtomicMove.retry(source, target) {
            if (++attempts < 3) throw AccessDeniedException(source.toString())
        }

        assertEquals(3, attempts)
    }

    @Test
    fun `a persistent sharing violation is bounded and reports both paths`() {
        val source = Path.of("source")
        val target = Path.of("target")
        var attempts = 0

        val failure = assertFailsWith<java.io.IOException> {
            AtomicMove.retry(source, target) {
                attempts++
                throw AccessDeniedException(source.toString())
            }
        }

        assertEquals(5, attempts)
        assertTrue(failure.message.orEmpty().contains("source -> target"))
    }

    @Test
    fun `unsupported atomic move never falls back or retries`() {
        var attempts = 0
        assertFailsWith<AtomicMoveNotSupportedException> {
            AtomicMove.retry(Path.of("source"), Path.of("target")) {
                attempts++
                throw AtomicMoveNotSupportedException("source", "target", "unsupported")
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `a missing source or an occupied target fails at once with its own type`() {
        listOf(NoSuchFileException("source"), FileAlreadyExistsException("target")).forEach { error ->
            var attempts = 0
            val failure = assertFailsWith<java.io.IOException> {
                AtomicMove.retry(Path.of("source"), Path.of("target")) {
                    attempts++
                    throw error
                }
            }
            assertSame(error, failure)
            assertEquals(1, attempts)
        }
    }
}
