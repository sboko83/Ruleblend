package dev.ruleblend.core.storage

import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode

/** A fresh checkout's index preserves Unix modes even when its filesystem cannot represent them. */
internal fun gitSkillExecutableFiles(checkout: Path, directory: Path): Set<String> {
    require(directory.normalize().startsWith(checkout.normalize())) { "Skill path escapes the checkout" }
    val relative = checkout.relativize(directory).joinToString("/")
    val prefix = if (relative.isEmpty()) "" else "$relative/"
    return Git.open(checkout.toFile()).use { git ->
        val index = git.repository.readDirCache()
        buildSet {
            for (i in 0 until index.entryCount) {
                val entry = index.getEntry(i)
                val path = entry.pathString
                if (path.startsWith(prefix) || path == relative || relative.startsWith("$path/")) {
                    require(entry.fileMode != FileMode.SYMLINK) { "Skill contains a symbolic link: $path" }
                    if (path.startsWith(prefix) && entry.fileMode == FileMode.EXECUTABLE_FILE) {
                        add(path.removePrefix(prefix))
                    }
                }
            }
        }
    }
}
