package dev.ruleblend.core.storage

import dev.ruleblend.core.model.LineEnding
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import kotlin.io.path.exists
import kotlin.io.path.readText

/** One policy shared by JGit and native Git, without changing global Git preferences. */
internal class LibraryLineEndings(private val root: Path) {
    fun isConfigured(git: Git, ending: LineEnding): Boolean {
        val config = git.repository.config
        return config.getString("core", null, "autocrlf") == "false" &&
            config.getString("core", null, "eol") == ending.gitValue &&
            listOf(root.resolve(ATTRIBUTES_FILE), git.repository.directory.toPath().resolve("info/attributes"))
                .all { it.exists() && it.readText().contains(MANAGED_ATTRIBUTES) }
    }

    fun configure(git: Git, ending: LineEnding) {
        val config = git.repository.config
        val before = config.toText()
        config.setBoolean("core", null, "autocrlf", false)
        config.setString("core", null, "eol", ending.gitValue)
        config.setBoolean("merge", null, "renormalize", true)
        if (config.toText() != before) config.save()
        updateAttributes(root.resolve(ATTRIBUTES_FILE))
        // Highest precedence protects payloads even when an imported tree has .gitattributes.
        updateAttributes(git.repository.directory.toPath().resolve("info/attributes"))
    }

    /** Converts only clean definitions; edited and staged files retain their exact bytes. */
    fun normalizeCleanDefinitions(git: Git, ending: LineEnding): List<String> {
        val repository = git.repository
        val head = repository.resolve("HEAD") ?: return emptyList()
        val index = repository.readDirCache()
        val clean = mutableListOf<String>()
        RevWalk(repository).use { revisions ->
            TreeWalk(repository).use { tree ->
                tree.addTree(revisions.parseCommit(head).tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val path = tree.pathString
                    if (!isDefinition(path)) continue
                    if (tree.getFileMode(0) != FileMode.REGULAR_FILE) continue
                    val blobId = tree.getObjectId(0)
                    if (index.getEntry(path)?.objectId != blobId) continue
                    val target = root.resolve(path)
                    if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) continue
                    val committed = repository.open(blobId).bytes
                    val current = Files.readAllBytes(target)
                    if (!committed.toString(Charsets.UTF_8).encodeToByteArray().contentEquals(committed) ||
                        !current.toString(Charsets.UTF_8).encodeToByteArray().contentEquals(current)) continue
                    val canonical = LineEnding.LF.apply(committed.toString(Charsets.UTF_8))
                    if (LineEnding.LF.apply(current.toString(Charsets.UTF_8)) != canonical) continue
                    val formatted = ending.apply(canonical).encodeToByteArray()
                    if (!current.contentEquals(formatted) &&
                        !AtomicWrite.writeIfUnchanged(target, formatted, FileIdentity.of(current))) continue
                    clean += path
                }
            }
        }
        return clean
    }

    private fun updateAttributes(path: Path) {
        val previousBytes = path.takeIf { it.exists() }?.let(Files::readAllBytes)
        val previous = previousBytes?.toString(Charsets.UTF_8).orEmpty()
        require(previousBytes == null || previous.encodeToByteArray().contentEquals(previousBytes)) {
            "Git attributes are not valid UTF-8: $path"
        }
        val start = previous.indexOf(BEGIN)
        val end = previous.indexOf(END)
        val updated = if (start < 0 && end < 0) {
            previous + (if (previous.isEmpty() || previous.endsWith('\n')) "" else "\n") + MANAGED_ATTRIBUTES
        } else {
            require(start >= 0 && end >= start &&
                (start == 0 || previous[start - 1] == '\n') &&
                (end == 0 || previous[end - 1] == '\n') &&
                previous.indexOf(BEGIN, start + BEGIN.length) < 0 &&
                previous.indexOf(END, end + END.length) < 0 &&
                (end + END.length == previous.length || previous[end + END.length] in "\r\n")) {
                "Invalid Ruleblend line-ending attributes in $path"
            }
            var after = end + END.length
            if (previous.startsWith("\r\n", after)) after += 2 else if (previous.startsWith("\n", after)) after++
            previous.replaceRange(start, after, MANAGED_ATTRIBUTES)
        }
        if (updated != previous) {
            check(AtomicWrite.writeIfUnchanged(path, updated, previousBytes?.let(FileIdentity::of))) {
                "Git attributes changed while configuring line endings: $path"
            }
        }
    }

    companion object {
        const val ATTRIBUTES_FILE = ".gitattributes"
        private const val BEGIN = "# BEGIN Ruleblend line endings"
        private const val END = "# END Ruleblend line endings"
        private val definitionPath = Regex("(?:blocks/[^/]+\\.md|(?:groups|profiles)/[^/]+\\.yaml|skills/[^/]+/meta\\.yaml)")
        val MANAGED_ATTRIBUTES = """
            $BEGIN
            /blocks/*.md text !eol
            /groups/*.yaml text !eol
            /profiles/*.yaml text !eol
            /skills/*/meta.yaml text !eol
            /skills/*/files/** -text !eol
            /.gitattributes -text !eol
            $END
        """.trimIndent() + "\n"

        fun isDefinition(path: String): Boolean =
            definitionPath.matches(path)
    }
}
