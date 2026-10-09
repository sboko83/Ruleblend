package dev.ruleblend.core

import com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE
import java.io.Closeable
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/** Prevents an atomic replacement using the host filesystem's real access restrictions. */
internal fun blockFileReplacement(file: Path): Closeable {
    if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
        val previous = Files.getPosixFilePermissions(file.parent)
        Files.setPosixFilePermissions(file.parent, previous - setOf(
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE,
        ))
        return Closeable { Files.setPosixFilePermissions(file.parent, previous) }
    }
    val created = !Files.exists(file)
    if (created) Files.createFile(file)
    val channel = Files.newByteChannel(file, READ, NOSHARE_DELETE)
    return Closeable {
        channel.close()
        if (created) Files.delete(file)
    }
}

/**
 * Git objects are read-only on Windows; remove only the fixture's own tree.
 * JGit's background filesystem probe may add or remove files mid-walk, so vanished paths are
 * skipped and the walk is repeated until the tree is gone.
 */
internal fun deleteFixtureTree(root: Path) {
    repeat(3) { attempt ->
        try {
            if (!Files.exists(root, NOFOLLOW_LINKS)) return
            val paths = Files.walk(root).use { it.sorted(Comparator.reverseOrder()).toList() }
            paths.forEach { path ->
                try {
                    Files.getFileAttributeView(path, DosFileAttributeView::class.java, NOFOLLOW_LINKS)?.setReadOnly(false)
                } catch (ignored: NoSuchFileException) {
                }
                Files.deleteIfExists(path)
            }
            return
        } catch (error: IOException) {
            if (attempt == 2 || error !is DirectoryNotEmptyException) throw error
        } catch (error: UncheckedIOException) {
            if (attempt == 2 || error.cause !is NoSuchFileException) throw error
        }
    }
}
