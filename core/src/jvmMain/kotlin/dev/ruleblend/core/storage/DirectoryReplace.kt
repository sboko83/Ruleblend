package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import kotlin.io.path.createDirectories
import kotlin.io.path.name

/**
 * Replaces the tree under a directory while the directory itself stays where readers look for it.
 *
 * Swapping two whole directories by rename leaves a moment in which neither is at the target path:
 * an assistant scanning its skills then misses the skill, and a directory another tool creates in
 * that moment makes the second rename fail. Here an existing directory is updated in place instead:
 * every file lands by an atomic rename from a complete sibling staging tree, [replace]'s `last` file
 * after all the others, and files the new tree no longer has go at the very end. A reader can briefly
 * see old and new files side by side, never a missing directory or a half-written file.
 *
 * Nothing is followed through a symbolic link: links are renamed or deleted as entries, and a target
 * that is itself a link or a plain file is moved aside whole.
 */
object DirectoryReplace {
    /**
     * Makes [target] hold exactly the tree [stage] writes into the staging directory it is handed.
     * What [target] held is copied to [backup] and left there for [restore] until the caller drops
     * it; a [target] that did not exist leaves [backup] absent. [beforeApply] runs once staging is
     * complete, directly before the first change, and may still refuse. [onChange] follows every
     * change made to [target].
     * A failure after the first change puts the previous tree back before it is rethrown.
     */
    fun replace(
        target: Path,
        backup: Path,
        last: String? = null,
        beforeApply: () -> Unit = {},
        onChange: () -> Unit = {},
        stage: (Path) -> Unit,
    ) {
        val parent = requireNotNull(target.parent) { "Directory has no parent: $target" }
        parent.createDirectories()
        check(!Files.exists(backup, NOFOLLOW_LINKS)) { "Previous rollback remains at $backup; restore it before replacing $target" }
        val staged = Files.createTempDirectory(parent, ".${target.name}.staging-")
        var applied = false
        var failure: Throwable? = null
        try {
            stage(staged)
            beforeApply()
            when {
                Files.isDirectory(target, NOFOLLOW_LINKS) -> {
                    try {
                        copyTree(target, backup)
                    } catch (error: Throwable) {
                        runCatching { deleteTree(backup) }.onFailure(error::addSuppressed)
                        throw error
                    }
                    applied = true
                    merge(staged, target, last, onChange)
                }
                Files.exists(target, NOFOLLOW_LINKS) -> {
                    // A link or a plain file has no tree to update: it is set aside whole, unfollowed.
                    AtomicMove.move(target, backup)
                    onChange()
                    try {
                        AtomicMove.move(staged, target)
                    } catch (error: Throwable) {
                        if (!Files.exists(target, NOFOLLOW_LINKS))
                            runCatching { AtomicMove.move(backup, target) }.onFailure(error::addSuppressed)
                        throw error
                    }
                    onChange()
                }
                else -> {
                    AtomicMove.move(staged, target)
                    onChange()
                }
            }
        } catch (error: Throwable) {
            if (applied) runCatching { restore(target, backup, last, onChange) }.onFailure(error::addSuppressed)
            val reported = if (Files.exists(backup, NOFOLLOW_LINKS)) {
                IllegalStateException("${error.message ?: error}; rollback remains at $backup for $target", error)
            } else error
            failure = reported
            throw reported
        } finally {
            runCatching { deleteTree(staged) }.onFailure { cleanup ->
                if (failure == null) throw cleanup
                failure.addSuppressed(cleanup)
            }
        }
    }

    /**
     * Puts back what [replace] kept in [backup] and drops [backup]. An existing directory gets its
     * previous files back in place, the same way [replace] wrote the new ones; with no [backup],
     * [target] did not exist before and is removed.
     */
    fun restore(target: Path, backup: Path, last: String? = null, onChange: () -> Unit = {}) {
        when {
            !Files.exists(backup, NOFOLLOW_LINKS) -> {
                deleteTree(target)
                onChange()
            }
            Files.isDirectory(backup, NOFOLLOW_LINKS) && Files.isDirectory(target, NOFOLLOW_LINKS) -> {
                merge(backup, target, last, onChange)
                deleteTree(backup)
            }
            else -> {
                deleteTree(target)
                AtomicMove.move(backup, target)
                onChange()
            }
        }
    }

    /** Deletes [path] and everything under it; a symbolic link is deleted itself, never followed. */
    fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    /** Moves every entry of [source] into [target], [last] after the rest, then drops what [source] lacks. */
    private fun merge(source: Path, target: Path, last: String?, onChange: () -> Unit) {
        val entries = relativeEntries(source)
        val (directories, leaves) = entries.partition { Files.isDirectory(source.resolve(it), NOFOLLOW_LINKS) }
        directories.forEach { ensureDirectory(target, it, onChange) }
        leaves.sortedBy { key(it) == last }.forEach { relative ->
            relative.parent?.let { ensureDirectory(target, it, onChange) }
            val destination = target.resolve(relative.toString())
            if (Files.isDirectory(destination, NOFOLLOW_LINKS)) {
                deleteTree(destination)
                onChange()
            }
            AtomicMove.move(source.resolve(relative.toString()), destination, replace = true)
            onChange()
        }
        val wanted = entries.mapTo(HashSet(), ::key)
        relativeEntries(target).filter { key(it) !in wanted }.forEach { extra ->
            // An extra directory goes with its contents, so its children are already gone here.
            val path = target.resolve(extra.toString())
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                deleteTree(path)
                onChange()
            }
        }
    }

    /** Creates each missing level of [relative] under [root], replacing a file or link standing in its way. */
    private fun ensureDirectory(root: Path, relative: Path, onChange: () -> Unit) {
        var current = root
        relative.forEach { name ->
            current = current.resolve(name.toString())
            if (Files.isDirectory(current, NOFOLLOW_LINKS)) return@forEach
            if (Files.exists(current, NOFOLLOW_LINKS)) Files.delete(current)
            Files.createDirectory(current)
            onChange()
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { path ->
                Files.copy(path, to.resolve(from.relativize(path).toString()), COPY_ATTRIBUTES, NOFOLLOW_LINKS)
            }
        }
    }

    /** Entries under [root], parents before children; links are listed, not entered. */
    private fun relativeEntries(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { it != root }.map { root.relativize(it) }.toList()
    }.sortedBy(::key)

    private fun key(relative: Path): String = relative.joinToString("/")
}
