package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.config.RemoteGitConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

class LibraryLineEndingsTest {
    private val roots = mutableListOf<Path>()
    private fun root() = Files.createTempDirectory("ruleblend-eol").also(roots::add)

    @AfterTest fun cleanUp() = roots.forEach(::deleteFixtureTree)

    @Test fun `each output choice saves definitions with canonical LF in Git`() {
        for (ending in LineEnding.entries) {
            val root = root()
            val git = LibraryGit(root, lineEnding = { ending })
            val repository = LibraryRepository(root, git)
            repository.init()
            repository.saveBlock(Block("one", "One", content = "first\r\nsecond\rthird\n"))
            repository.saveGroup(Group("group", "Group", blockIds = listOf("one")))
            repository.saveProfile(Profile("profile", "Profile", blockIds = listOf("one")))
            repository.createSkill(Skill("local", "Local", content = "# Skill\nBody\n"))
            for (path in listOf("blocks/one.md", "groups/group.yaml", "profiles/profile.yaml", "skills/local/meta.yaml")) {
                val text = root.resolve(path).readText()
                assertEquals(ending.apply(text), text, path)
                val committed = committed(root, path).decodeToString()
                assertFalse('\r' in committed, path)
                assertEquals(LineEnding.LF.apply(text), committed)
            }
            assertEquals("first\nsecond\nthird\n", repository.loadBlock("one")!!.content)
            assertEquals(ending.apply("# Skill\nBody\n"), repository.loadSkill("local")!!.content)
            Git.open(root.toFile()).use { opened ->
                assertEquals("false", opened.repository.config.getString("core", null, "autocrlf"))
                assertEquals(ending.gitValue, opened.repository.config.getString("core", null, "eol"))
                assertTrue(opened.status().call().isClean)
            }
        }
    }

    @Test fun `changing output format rewrites clean definitions without revisions or commits`() {
        val root = root()
        var ending = LineEnding.LF
        val git = LibraryGit(root, lineEnding = { ending })
        val repository = LibraryRepository(root, git)
        repository.init()
        val block = repository.saveBlock(Block("one", "One", content = "first\nsecond\n"))
        val head = Git.open(root.toFile()).use { it.repository.resolve("HEAD") }
        for (choice in listOf(LineEnding.CRLF, LineEnding.LF, LineEnding.CRLF)) {
            ending = choice
            git.configureLineEndings()
            assertEquals(choice.apply(root.resolve("blocks/one.md").readText()), root.resolve("blocks/one.md").readText())
            assertEquals(block, repository.loadBlock("one"))
            assertEquals(block, repository.saveBlock(block.copy(content = choice.apply(block.content))))
            Git.open(root.toFile()).use {
                assertEquals(head, it.repository.resolve("HEAD"))
                assertTrue(it.status().call().isClean)
            }
        }
    }

    @Test fun `payload bytes and nested attributes remain unchanged on every format switch`() {
        val root = root()
        var ending = LineEnding.LF
        val git = LibraryGit(root, lineEnding = { ending })
        val repository = LibraryRepository(root, git)
        repository.init()
        val files = listOf(
            SkillFile("SKILL.md", "# Skill\r\nBody\r\n".encodeToByteArray()),
            SkillFile("script.cmd", "@echo off\r\necho example\r\n".encodeToByteArray()),
            SkillFile("binary.dat", byteArrayOf(0, 13, 10, -1, 10)),
            SkillFile(".gitattributes", "* text eol=crlf\n".encodeToByteArray()),
        )
        repository.writeSkill(SkillSnapshot(Skill("raw", "Raw", content = files.first().bytes.decodeToString()), files))
        for (choice in LineEnding.entries) {
            ending = choice
            git.configureLineEndings()
            for (file in files) {
                val path = "skills/raw/files/${file.path}"
                assertContentEquals(file.bytes, root.resolve(path).readBytes(), path)
                assertContentEquals(file.bytes, committed(root, path), path)
            }
            Git.open(root.toFile()).use { assertTrue(it.status().call().isClean) }
        }
    }

    @Test fun `legacy CRLF history is normalized once while staged foreign edits survive`() {
        val root = root()
        val blockPath = root.resolve("blocks/one.md").also { it.parent.createDirectories() }
        blockPath.writeText(LineEnding.CRLF.apply(BlockFile.serialize(Block("one", "One", version = 7, content = "body\n"))))
        val foreign = root.resolve("notes.txt")
        foreign.writeText("original\r\n")
        val attributes = root.resolve(".gitattributes")
        val before = "# user prefix\r\n*.png binary\r\n"
        attributes.writeText(before)
        Git.init().setDirectory(root.toFile()).call().use { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", false)
            git.repository.config.save()
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Seed legacy history").setAuthor("Test", "test@example.com").call()
            foreign.writeText("staged edit\r\n")
            git.add().addFilepattern("notes.txt").call()
        }

        val git = LibraryGit(root)
        git.init()

        assertEquals(7, BlockFile.parse("one", blockPath.readText()).version)
        assertFalse('\r' in committed(root, "blocks/one.md").decodeToString())
        assertTrue(attributes.readText().startsWith(before))
        assertEquals("staged edit\r\n", foreign.readText())
        assertEquals("original\r\n", committed(root, "notes.txt").decodeToString())
        Git.open(root.toFile()).use { assertEquals(setOf("notes.txt"), it.status().call().changed) }
        val commits = git.history().commits
        git.init()
        assertEquals(commits, git.history().commits)
    }

    @Test fun `a native style CRLF clone restores payloads converted by nested upstream attributes`() {
        val source = root()
        val repository = LibraryRepository(source)
        repository.init()
        val files = listOf(
            SkillFile("SKILL.md", "# Skill\nBody\n".encodeToByteArray()),
            SkillFile(".gitattributes", "* text eol=crlf\n".encodeToByteArray()),
            SkillFile("binary.dat", byteArrayOf(0, 13, 10, -1)),
        )
        repository.writeSkill(SkillSnapshot(Skill("raw", "Raw", content = files.first().bytes.decodeToString()), files))
        val clone = root()
        Git.cloneRepository().setURI(source.toUri().toString()).setDirectory(clone.toFile())
            .setNoCheckout(true).call().use { git ->
                git.repository.config.setBoolean("core", null, "autocrlf", true)
                git.repository.config.save()
                git.checkout().setStartPoint("HEAD").setAllPaths(true).call()
            }
        assertTrue(clone.resolve("skills/raw/files/SKILL.md").readText().contains("\r\n"))

        LibraryGit(clone).init()

        for (file in files) assertContentEquals(file.bytes, clone.resolve("skills/raw/files/${file.path}").readBytes())
        Git.open(clone.toFile()).use { git ->
            assertTrue(git.status().call().isClean)
            val path = "skills/raw/files/SKILL.md"
            assertEquals(Files.size(clone.resolve(path)), git.repository.readDirCache().getEntry(path).length.toLong())
        }
    }

    @Test fun `malformed managed attribute markers never overwrite foreign text`() {
        val root = root()
        val path = root.resolve(".gitattributes")
        val original = "# user\r\n# BEGIN Ruleblend line endings\r\n*.png binary\r\n"
        path.writeText(original)
        assertFailsWith<IllegalArgumentException> { LibraryGit(root).init() }
        assertEquals(original, path.readText())
    }

    @Test fun `foreign attribute bytes and their staged edit survive setting changes`() {
        val root = root()
        val git = LibraryGit(root)
        val repository = LibraryRepository(root, git)
        repository.init()
        repository.saveBlock(Block("one", "One", content = "body\n"))
        val attributes = root.resolve(".gitattributes")
        val edited = "# user prefix\r\n" + attributes.readText() + "# user suffix\r\n"
        attributes.writeText(edited)
        val head = Git.open(root.toFile()).use { opened ->
            opened.add().addFilepattern(".gitattributes").call()
            opened.repository.resolve("HEAD")
        }

        git.configureLineEndings(LineEnding.CRLF)

        assertEquals(edited, attributes.readText())
        Git.open(root.toFile()).use { opened ->
            assertEquals(head, opened.repository.resolve("HEAD"))
            assertEquals(setOf(".gitattributes"), opened.status().call().changed)
        }
    }

    @Test fun `normalization does not win over a concurrent edit from an older device`() {
        val root = root()
        val remote = root().resolve("remote.git")
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close()
        val path = root.resolve("blocks/one.md").also { it.parent.createDirectories() }
        path.writeText(LineEnding.CRLF.apply(BlockFile.serialize(Block("one", "One", content = "initial\n"))))
        Git.init().setDirectory(root.toFile()).call().use { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", false)
            git.repository.config.save()
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Seed legacy history").setAuthor("Test", "test@example.com").call()
            if (git.repository.branch != "main") git.branchRename().setNewName("main").call()
            git.push().setRemote(remote.toUri().toString())
                .setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call()
        }
        val oldDevice = root()
        Git.cloneRepository().setURI(remote.toUri().toString()).setBranch("main").setDirectory(oldDevice.toFile()).call().close()
        val git = LibraryGit(root)
        git.init()
        oldDevice.resolve("blocks/one.md").writeText(
            LineEnding.CRLF.apply(BlockFile.serialize(Block("one", "One", version = 2, content = "real remote edit\n"))),
        )
        Git.open(oldDevice.toFile()).use { old ->
            old.add().addFilepattern("blocks/one.md").call()
            old.commit().setMessage("Edit from older device").setAuthor("Test", "test@example.com").call()
            old.push().call()
        }

        val result = git.sync(RemoteGitConfig(remote.toUri().toString()))

        val block = BlockFile.parse("one", path.readText())
        assertEquals("real remote edit\n", block.content)
        assertEquals(2, block.version)
        assertTrue(result.conflicts.isEmpty())
    }

    private fun committed(root: Path, path: String): ByteArray = Git.open(root.toFile()).use { git ->
        RevWalk(git.repository).use { revisions ->
            val head = revisions.parseCommit(git.repository.resolve("HEAD"))
            TreeWalk.forPath(git.repository, path, head.tree)!!.use { tree ->
                git.repository.open(tree.getObjectId(0)).bytes
            }
        }
    }
}
