package dev.ruleblend.core.storage

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/** Writes text via temp file + rename, so a target file is never left half-written. */
object AtomicWrite {
    private class ChangedDuringWrite : RuntimeException()

    fun write(path: Path, text: String) {
        write(path, text.encodeToByteArray())
    }

    fun write(path: Path, bytes: ByteArray) {
        write(path, bytes, expected = null, guarded = false)
    }

    /**
     * [write], but only while [path] still holds what [expected] describes — null meaning it must
     * still not exist. Returns false when it no longer does: the replacement is dropped and the file
     * is left as the other writer made it. The check sits directly before the rename, so the window
     * an external editor could still slip through is one syscall wide.
     */
    fun writeIfUnchanged(path: Path, text: String, expected: FileIdentity?): Boolean =
        writeIfUnchanged(path, text.encodeToByteArray(), expected)

    fun writeIfUnchanged(path: Path, bytes: ByteArray, expected: FileIdentity?): Boolean =
        write(path, bytes, expected, guarded = true)

    private fun write(path: Path, bytes: ByteArray, expected: FileIdentity?, guarded: Boolean): Boolean {
        val directory = path.toAbsolutePath().parent
        directory.createDirectories()
        val temp = createTemp(directory, path.fileName.toString())
        try {
            Files.write(temp, bytes)
            keepPermissions(path, temp)
            AtomicMove.move(temp, path, replace = true) {
                if (guarded && FileIdentity.of(path) != expected) throw ChangedDuringWrite()
            }
            return true
        } catch (e: Throwable) {
            runCatching { temp.deleteIfExists() }.onFailure(e::addSuppressed)
            if (e is ChangedDuringWrite) return false
            throw e
        }
    }

    /**
     * A sibling file no other writer holds. Not [Files.createTempFile]: that one is created
     * owner-only (0600) on POSIX, and the rename would carry that mode onto the target — a project's
     * `CLAUDE.md` would stop being readable by anyone but the user. [Files.createFile] applies the
     * process umask, the mode an editor gives a new file.
     */
    private fun createTemp(directory: Path, name: String): Path {
        while (true) {
            val candidate = directory.resolve(".$name.${UUID.randomUUID().toString().take(8)}.tmp")
            try {
                return Files.createFile(candidate)
            } catch (_: FileAlreadyExistsException) {
                // Another writer picked the same suffix; try the next one.
            }
        }
    }

    /**
     * The rename replaces the file's mode along with its content, so an existing file's mode is
     * carried over: a script that was executable stays executable. Filesystems without POSIX
     * attributes have no mode to carry.
     */
    private fun keepPermissions(target: Path, temp: Path) {
        if (!target.exists()) return
        runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target)) }
    }
}
