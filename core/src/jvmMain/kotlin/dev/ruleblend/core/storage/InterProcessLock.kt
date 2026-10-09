package dev.ruleblend.core.storage

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.createDirectories

/** Raised when another Ruleblend process kept the lock for longer than the caller was willing to wait. */
class LockTimeoutException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Advisory OS file lock serializing library and config mutations across Ruleblend processes (the GUI
 * app and the MCP server). An internal [ReentrantLock] serializes threads within the process and
 * makes nesting safe — the OS lock is taken only on the outermost frame, because
 * [java.nio.channels.FileLock] throws on overlapping acquisition from the same JVM.
 *
 * Advisory only: correctness relies on every writer routing through [LibraryGit.withLock],
 * [dev.ruleblend.core.config.ConfigStore.update] or [TargetMutationCoordinator], and on one shared
 * instance per process.
 */
class InterProcessLock(private val file: Path, private val timeoutMs: Long = 10_000) {

    private val threadLock = ReentrantLock()
    private var channel: FileChannel? = null

    /** Runs [body] holding the lock. Reentrant. Throws [LockTimeoutException] on timeout. */
    fun <T> withLock(body: () -> T): T {
        if (!threadLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw LockTimeoutException("Another Ruleblend thread is holding $file for over ${timeoutMs / 1000}s")
        }
        try {
            if (threadLock.holdCount > 1) return body()
            acquireFileLock()
            try {
                return body()
            } finally {
                channel?.close() // Closing the channel releases the FileLock.
                channel = null
            }
        } finally {
            threadLock.unlock()
        }
    }

    private fun acquireFileLock() {
        file.parent?.createDirectories()
        val ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            // Overlapping acquisition from this JVM (another InterProcessLock instance) reads the
            // same as a lock held by another process: busy, keep retrying until the deadline.
            while (tryLock(ch) == null) {
                if (System.currentTimeMillis() >= deadline) {
                    throw LockTimeoutException("Another Ruleblend process is holding $file for over ${timeoutMs / 1000}s")
                }
                Thread.sleep(100)
            }
            channel = ch
        } catch (e: Throwable) {
            ch.close()
            throw e
        }
    }

    /**
     * Null while the lock is busy. Only the two "busy" answers are folded into that: a `null` from
     * the OS and the overlap the JVM reports for its own channels. Any other failure — a filesystem
     * that cannot lock at all — is raised at once rather than retried into a timeout that would
     * blame another Ruleblend process.
     */
    private fun tryLock(ch: FileChannel): FileLock? = try {
        ch.tryLock()
    } catch (_: OverlappingFileLockException) {
        null
    }
}
