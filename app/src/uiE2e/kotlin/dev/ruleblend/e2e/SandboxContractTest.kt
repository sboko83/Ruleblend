@file:Suppress("DEPRECATION")

package dev.ruleblend.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class SandboxContractTest {
    @Test
    fun childHoldsTheNativeLockUntilInputCloses() {
        val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
        val lock = Files.createTempFile(root, "lock-holder-", ".lock")
        val java = ProcessHandle.current().info().command().orElseThrow()
        val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            LockHolder::class.java.name, lock.toString()).redirectErrorStream(true).start()
        try {
            val ready = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }
            assertEquals("ready", ready.get(10, TimeUnit.SECONDS))
            FileChannel.open(lock, WRITE).use { channel ->
                assertNull(channel.tryLock(), "the other JVM owns the native lock")
                child.outputStream.close()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, child.exitValue())
                channel.lock().use { assertTrue(it.isValid) }
            }
        } finally {
            if (child.isAlive) child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            Files.delete(lock)
        }
    }

    @Test
    fun H07_writesAndSymlinkEscapesAreRejected() {
        val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
        val home = root.resolve("home")
        assertEquals(home.toString(), System.getProperty("user.home"))
        assertEquals(home.toString(), System.getenv("HOME"))
        assertEquals(home.resolve(".codex").toString(), System.getenv("CODEX_HOME"))
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertEquals(home.toString(), System.getenv("USERPROFILE"))
            assertEquals(home.resolve("AppData/Roaming").toString(), System.getenv("APPDATA"))
            assertTrue(Files.isDirectory(Path.of(requireNotNull(System.getenv("SystemRoot")))))
        }
        val boundary = Files.createTempDirectory(root, "guard-")
        val outside = root.resolve("neighbor.txt")
        val before = outside.readText()
        val link = boundary.resolve("escape")
        Files.createSymbolicLink(link, root)
        val dangling = boundary.resolve("dangling")
        Files.createSymbolicLink(dangling, boundary.resolve("missing"))
        val previous = System.getSecurityManager()
        System.setSecurityManager(SandboxWrites(boundary))
        try {
            boundary.resolve("allowed.txt").writeText("fixture")
            assertFailsWith<IllegalArgumentException> { outside.writeText("forbidden") }
            assertFailsWith<IllegalArgumentException> { link.resolve("neighbor.txt").writeText("forbidden") }
            assertFailsWith<IllegalArgumentException> { Files.delete(outside) }
            Files.delete(dangling)
            Files.delete(link)
            val java = Path.of(ProcessHandle.current().info().command().orElseThrow())
            assertTrue(Files.isExecutable(java))
            assertFailsWith<SecurityException> { ProcessBuilder(java.toString(), "-version").start() }
        } finally {
            System.setSecurityManager(previous)
        }
        assertEquals(before, outside.readText())
        assertFalse(Files.exists(dangling))
        assertFalse(Files.exists(link))
        assertFalse(Files.exists(root.resolve("forbidden.txt")))
    }
}
