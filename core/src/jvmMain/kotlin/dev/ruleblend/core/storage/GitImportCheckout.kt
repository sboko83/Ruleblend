package dev.ruleblend.core.storage

import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/** Bounds are checked against Git objects before checkout, including paths outside discovery roots. */
data class GitImportLimits(
    val maxFiles: Int = 20_000,
    val maxDepth: Int = 16,
    val maxFileBytes: Long = 10 * 1024 * 1024,
    val maxRepositoryBytes: Long = 100 * 1024 * 1024,
    val maxSubagentBytes: Long = 1024 * 1024,
) {
    init {
        require(maxFiles > 0 && maxDepth > 0 && maxFileBytes > 0 && maxRepositoryBytes > 0 && maxSubagentBytes > 0)
    }
}

internal fun Git.prepareImportCheckout(limits: GitImportLimits): List<String> {
    val paths = mutableListOf<String>()
    var total = 0L
    RevWalk(repository).use { revisions ->
        val commit = revisions.parseCommit(repository.resolve("HEAD") ?: error("Repository has no HEAD revision"))
        TreeWalk(repository).use { tree ->
            tree.addTree(commit.tree)
            tree.isRecursive = true
            while (tree.next()) {
                val path = tree.pathString
                require(paths.size < limits.maxFiles) { "Repository exceeds ${limits.maxFiles} files" }
                require(path.count { it == '/' } < limits.maxDepth) { "Repository path exceeds depth ${limits.maxDepth}: $path" }
                require(tree.getFileMode(0) in listOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) {
                    "Repository contains a symbolic link or unsupported Git mode: $path"
                }
                val size = repository.open(tree.getObjectId(0)).size
                require(size <= limits.maxFileBytes) { "Repository file exceeds ${limits.maxFileBytes} bytes: $path" }
                total += size
                require(total <= limits.maxRepositoryBytes) { "Repository exceeds ${limits.maxRepositoryBytes} bytes" }
                paths.add(path)
            }
        }
    }
    checkoutPortableTree()
    return paths
}

internal fun resolveGitImportPath(root: Path, declared: String): Path {
    require(declared.isNotBlank()) { "Plugin path is empty" }
    val relative = Path.of(declared)
    require(!relative.isAbsolute) { "Plugin path must be relative: $declared" }
    val resolved = root.resolve(relative).normalize()
    require(resolved.startsWith(root.normalize())) { "Plugin path escapes the repository: $declared" }
    return resolved
}
