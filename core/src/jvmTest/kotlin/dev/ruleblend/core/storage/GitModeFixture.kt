package dev.ruleblend.core.storage

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode

/** Creates a Unix mode change without requiring Unix filesystem permissions on the test host. */
internal fun commitGitMode(git: Git, path: String, mode: FileMode) {
    val index = git.repository.lockDirCache()
    try {
        val editor = index.editor()
        editor.add(object : DirCacheEditor.PathEdit(path) {
            override fun apply(entry: DirCacheEntry) {
                entry.fileMode = mode
            }
        })
        check(editor.commit())
    } finally {
        index.unlock()
    }
    git.commit().setMessage("Change file mode").setAuthor("Test", "test@example.com").call()
}
