package dev.ruleblend.core.storage

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Retries transient Windows sharing violations without ever falling back to a non-atomic move. */
internal object AtomicMove {
    private val delaysMs = longArrayOf(40, 80, 160, 320)

    fun move(source: Path, target: Path, replace: Boolean = false, beforeAttempt: () -> Unit = {}) {
        retry(source, target) {
            beforeAttempt()
            if (replace) Files.move(source, target, REPLACE_EXISTING, ATOMIC_MOVE)
            else Files.move(source, target, ATOMIC_MOVE)
        }
    }

    internal fun retry(source: Path, target: Path, move: () -> Unit) {
        delaysMs.forEach { delay ->
            try {
                move()
                return
            } catch (error: IOException) {
                if (!error.isRetryable()) throw error
                try {
                    Thread.sleep(delay)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error.addSuppressed(interrupted)
                    throw error
                }
            }
        }
        try {
            move()
        } catch (error: IOException) {
            if (!error.isRetryable()) throw error
            throw IOException(
                "Atomic move $source -> $target failed after ${delaysMs.size + 1} attempts; " +
                    "source exists=${Files.exists(source)}, target exists=${Files.exists(target)}",
                error,
            )
        }
    }

    /** A missing source or an occupied destination does not clear by waiting; its type must reach the caller. */
    private fun IOException.isRetryable(): Boolean = when (this) {
        is AtomicMoveNotSupportedException, is NoSuchFileException, is FileAlreadyExistsException,
        is DirectoryNotEmptyException, is NotDirectoryException -> false
        else -> this is FileSystemException
    }
}
