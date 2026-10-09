package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InterProcessLockTest {

    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-lock")
        file = dir.resolve("ruleblend.lock")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `nested withLock is reentrant`() {
        val lock = InterProcessLock(file)
        val result = lock.withLock { lock.withLock { lock.withLock { 42 } } }
        assertEquals(42, result)
    }

    @Test
    fun `lock is released after the outermost frame`() {
        val lock = InterProcessLock(file)
        lock.withLock { lock.withLock { } }
        // A second instance can acquire immediately — nothing left held.
        InterProcessLock(file, timeoutMs = 500).withLock { }
    }

    @Test
    fun `a held lock times out a second acquirer`() {
        val holder = InterProcessLock(file)
        val contender = InterProcessLock(file, timeoutMs = 300)
        holder.withLock {
            assertFailsWith<IllegalStateException> { contender.withLock { } }
        }
    }

    @Test
    fun `a held thread lock times out instead of waiting indefinitely`() {
        val lock = InterProcessLock(file, timeoutMs = 200)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            lock.withLock {
                entered.countDown()
                release.await()
            }
        }.apply { start() }
        try {
            entered.await()
            assertFailsWith<LockTimeoutException> { lock.withLock { } }
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test
    fun `threads of one process are serialized`() {
        val lock = InterProcessLock(file)
        var inside = 0
        var maxInside = 0
        val threads = (1..4).map {
            Thread {
                repeat(20) {
                    lock.withLock {
                        inside++
                        maxInside = maxOf(maxInside, inside)
                        inside--
                    }
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertTrue(maxInside <= 1, "withLock bodies overlapped: $maxInside")
    }
}
