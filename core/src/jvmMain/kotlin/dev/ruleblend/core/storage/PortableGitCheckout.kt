package dev.ruleblend.core.storage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import dev.ruleblend.core.model.requirePortableFileTree

/** Library snapshots keep exact bytes; machine-wide newline preferences are not library edits. */
internal fun Git.preserveFileBytes(): Git = apply {
    repository.config.setBoolean("core", null, "autocrlf", false)
    repository.config.setString("core", null, "eol", "lf")
}

/** Populate a fresh no-checkout clone after disabling machine-wide newline conversion. */
internal fun Git.checkoutPortableTree() {
    preserveFileBytes()
    RevWalk(repository).use { revisions ->
        val commit = revisions.parseCommit(repository.resolve("HEAD"))
        TreeWalk(repository).use { tree ->
            tree.addTree(commit.tree)
            tree.isRecursive = true
            val paths = buildList { while (tree.next()) add(tree.pathString) }
            requirePortableFileTree(paths)
        }
    }
    checkout().setStartPoint("HEAD").setAllPaths(true).call()
}
