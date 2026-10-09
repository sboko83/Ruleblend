package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RemoteRefUpdate

class LibraryGitTest {

    private lateinit var root: Path
    private lateinit var remote: Path
    private lateinit var other: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-git")
        remote = Files.createTempDirectory("ruleblend-remote")
        other = Files.createTempDirectory("ruleblend-git-other")
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close()
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(root)
        deleteFixtureTree(remote)
        deleteFixtureTree(other)
    }

    @Test
    fun syncPushesHeadToConfiguredBranch() {
        val git = LibraryGit(root)
        git.init()
        commitFile(git, "blocks/one.md", "one")

        val result = git.syncRemote(config())

        assertEquals(RemoteSyncStatus.PUSHED, result.status)
        Git.open(remote.toFile()).use { bare ->
            assertNotNull(bare.repository.exactRef("refs/heads/main"))
        }
        assertEquals(RemoteSyncStatus.UP_TO_DATE, git.syncRemote(config()).status)
    }

    @Test
    fun `empty UI library with derived all group clones existing remote`() {
        val seed = LibraryGit(other)
        seed.init()
        commitFile(seed, other, "blocks/from-remote.md", "remote")
        seed.syncRemote(config())

        val local = LibraryGit(root)
        val repository = LibraryRepository(root, local)
        repository.init()
        repository.syncAllGroup()
        assertNotNull(Git.open(root.toFile()).use { it.repository.resolve("HEAD") })

        local.syncRemote(config())

        assertEquals("remote", root.resolve("blocks/from-remote.md").toFile().readText())
        assertEquals(
            Git.open(remote.toFile()).use { it.repository.resolve("refs/heads/main") },
            Git.open(root.toFile()).use { it.repository.resolve("HEAD") },
        )
    }

    @Test
    fun `a temporary remote ref lock retries like a concurrent push`() {
        assertTrue(isRetryablePushFailure(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, null))
        assertTrue(isRetryablePushFailure(RemoteRefUpdate.Status.REJECTED_OTHER_REASON, "failed to lock refs/heads/main"))
        assertFalse(isRetryablePushFailure(RemoteRefUpdate.Status.REJECTED_OTHER_REASON, "permission denied"))
    }

    @Test
    fun fetchRecordsTheConfiguredRemoteBranch() {
        val git = LibraryGit(root)
        git.init()
        commitFile(git, "blocks/one.md", "one")
        git.sync(config())

        git.fetch(config())

        Git.open(root.toFile()).use { repository ->
            assertNotNull(repository.repository.exactRef("refs/remotes/ruleblend-sync/main"))
        }
    }

    @Test
    fun syncDownFastForwardsRemoteLibraryAndRebuildsAll() {
        val git = LibraryGit(root)
        git.init()
        commitBlock(git, "one", version = 1, content = "initial")
        git.sync(config())
        cloneRemote()
        commitBlockInOther("one", version = 2, content = "remote")

        val result = git.syncDown(config())

        assertEquals("remote", BlockFile.parse("one", root.resolve("blocks/one.md").toFile().readText()).content)
        val all = LibraryRepository(root, git).loadGroup("all")
        assertEquals(listOf("one"), all?.blockIds)
        assertEquals(1, result.behind)
        assertEquals(0, result.ahead)
        assertFalse(result.merged)
    }

    @Test
    fun syncKeepsLocalConcurrentBlockAsTheNextRevision() {
        val git = LibraryGit(root)
        git.init()
        commitBlock(git, "one", version = 1, content = "initial")
        git.sync(config())
        cloneRemote()
        commitBlockInOther("one", version = 2, content = "remote")
        commitBlock(git, "one", version = 2, content = "local")

        val result = git.sync(config())

        val merged = BlockFile.parse("one", root.resolve("blocks/one.md").toFile().readText())
        assertEquals("local", merged.content)
        assertEquals(3, merged.version)
        assertEquals(1, result.behind)
        assertEquals(1, result.ahead)
        assertTrue(result.merged)
        assertEquals(listOf(RemoteSyncConflict("one", RemoteSyncConflictKind.BLOCK)), result.conflicts)
        Git.open(remote.toFile()).use { bare ->
            assertTrue(bare.log().add(bare.repository.resolve("refs/heads/main")).call().any { it.fullMessage.contains("remote") })
        }
    }

    @Test
    fun syncDownRestoresRemoteEditWhenLocalCopyWasDeleted() {
        val git = LibraryGit(root)
        git.init()
        commitBlock(git, "one", version = 1, content = "initial")
        git.sync(config())
        cloneRemote()
        commitBlockInOther("one", version = 2, content = "remote")
        Files.delete(root.resolve("blocks/one.md"))
        git.commit("Delete block one", Path.of("blocks", "one.md"))

        val result = git.syncDown(config())

        assertEquals("remote", BlockFile.parse("one", root.resolve("blocks/one.md").toFile().readText()).content)
        assertEquals(listOf(RemoteSyncConflict("one", RemoteSyncConflictKind.BLOCK, restored = true)), result.conflicts)
    }

    @Test
    fun syncDownKeepsLocalEditWhenRemoteCopyWasDeleted() {
        val git = LibraryGit(root)
        git.init()
        commitBlock(git, "one", version = 1, content = "initial")
        git.sync(config())
        cloneRemote()
        deleteBlockInOther("one")
        commitBlock(git, "one", version = 2, content = "local")

        git.syncDown(config())

        assertEquals("local", BlockFile.parse("one", root.resolve("blocks/one.md").toFile().readText()).content)
    }

    @Test
    fun emptyLibraryClonesAnExistingRemoteInsteadOfCreatingItsOwnHistory() {
        val source = LibraryGit(other)
        source.init()
        commitFile(source, other, "blocks/remote.md", BlockFile.serialize(Block("remote", "remote", content = "from remote")))
        source.sync(config())

        val git = LibraryGit(root)
        git.init()
        git.syncRemote(config())

        assertEquals("from remote", BlockFile.parse("remote", root.resolve("blocks/remote.md").toFile().readText()).content)
        assertEquals(1, git.history().commits)
    }

    @Test
    fun unrelatedHistoriesNeedAnExplicitImportMerge() {
        val git = LibraryGit(root)
        git.init()
        commitBlock(git, "local", version = 1, content = "local")
        val source = LibraryGit(other)
        source.init()
        commitFile(source, other, "blocks/remote.md", BlockFile.serialize(Block("remote", "remote", content = "remote")))
        source.sync(config())

        assertFailsWith<UnrelatedLibraryHistories> { git.syncRemote(config()) }
        git.syncRemote(config(), allowUnrelatedHistories = true)

        assertTrue(root.resolve("blocks/local.md").toFile().exists())
        assertTrue(root.resolve("blocks/remote.md").toFile().exists())
    }

    @Test
    fun replacementUsesAnArchiveBeforeSwitchingToTheRemoteLibrary() {
        val git = LibraryGit(root)
        git.init()
        commitBlock(git, "local", version = 1, content = "local")
        val archive = root.parent.resolve("before-replace.zip")
        val repository = LibraryRepository(root, git)
        LibraryArchive(root, repository).export(archive)

        val source = LibraryGit(other)
        source.init()
        commitFile(source, other, "blocks/remote.md", BlockFile.serialize(Block("remote", "remote", content = "remote")))
        source.sync(config())
        git.replaceWithRemote(config())

        assertEquals("local", LibraryArchive(root, LibraryRepository(root, git)).read(archive).blocks.single().content)
        assertTrue(root.resolve("blocks/remote.md").toFile().exists())
        assertFalse(root.resolve("blocks/local.md").toFile().exists())
    }

    @Test
    fun initWritesTheLibraryGitignore() {
        val git = LibraryGit(root)

        git.init()

        assertEquals(
            ".DS_Store\n.*.rollback\n.*.import-*\n",
            root.resolve(".gitignore").toFile().readText(),
        )
        root.resolve(".DS_Store").writeText("finder")
        root.resolve(".save.rollback").writeText("rollback")
        root.resolve(".skill.import-123").writeText("import")
        commitFile(git, "blocks/one.md", "one")
        Git.open(root.toFile()).use { repository ->
            assertTrue(repository.status().call().isClean)
        }
    }

    @Test
    fun unchangedPathDoesNotCommitBecauseOfAnUntrackedSibling() {
        val git = LibraryGit(root)
        git.init()
        commitFile(git, "blocks/one.md", "one")
        root.resolve("notes.txt").writeText("outside the library object")

        git.commit("Save blocks/one.md", Path.of("blocks", "one.md"))

        assertEquals(1, git.log(Path.of("blocks", "one.md")).size)
    }

    @Test
    fun commitIdentifiesTheRuleblendDevice() {
        val git = LibraryGit(root, hostname = { "test-host" })
        git.init()

        commitFile(git, "blocks/one.md", "one")

        Git.open(root.toFile()).use { repository ->
            val commit = repository.log().call().single()
            assertEquals("Ruleblend @ test-host", commit.authorIdent.name)
            assertEquals("Ruleblend @ test-host", commit.committerIdent.name)
            assertFalse(commit.authorIdent.emailAddress.isBlank())
        }
    }

    @Test
    fun automaticSyncRunsAfterEveryLocalCommit() {
        val config = config()
        val git = LibraryGit(root, remoteConfig = { config })
        git.init()

        commitFile(git, "blocks/one.md", "one")
        commitFile(git, "blocks/two.md", "two")
        git.awaitRemoteSync()

        Git.open(remote.toFile()).use { bare ->
            val commits = bare.log().add(bare.repository.resolve("refs/heads/main")).call().toList()
            assertEquals(2, commits.size)
        }
    }

    @Test
    fun automaticSyncRunsOnLaunchAndThrottlesWindowFocusForFiveMinutes() {
        var currentConfig = config().copy(automatic = false)
        var now = 1_000L
        val git = LibraryGit(root, remoteConfig = { currentConfig })
        git.init()
        commitFile(git, "blocks/one.md", "one")
        currentConfig = currentConfig.copy(automatic = true)

        assertTrue(git.scheduleAutomaticSyncOnLaunch())
        git.awaitRemoteSync()
        assertTrue(git.scheduleAutomaticSyncOnWindowFocus(now))
        git.awaitRemoteSync()
        now += 5 * 60 * 1_000L - 1
        assertFalse(git.scheduleAutomaticSyncOnWindowFocus(now))
        now += 1
        assertTrue(git.scheduleAutomaticSyncOnWindowFocus(now))
        git.awaitRemoteSync()
    }

    @Test
    fun `an automatic push never blocks the next save`() {
        val config = config()
        val git = LibraryGit(root, remoteConfig = { config })
        git.init()

        commitFile(git, "blocks/one.md", "one")
        // The next save takes the library write lock. If the push took it too, the push could only
        // finish after this block does, and awaiting it here would deadlock until the timeout.
        git.withLock { git.awaitRemoteSync(timeoutMillis = 10_000) }

        Git.open(remote.toFile()).use { bare ->
            assertNotNull(bare.repository.exactRef("refs/heads/main"))
        }
    }

    @Test
    fun failedAutomaticPushKeepsTheLocalCommit() {
        val missing = root.resolve("missing.git").toUri().toString()
        val git = LibraryGit(
            root,
            remoteConfig = { RemoteGitConfig(missing, automatic = true) },
        )
        git.init()

        commitFile(git, "blocks/one.md", "one")
        git.awaitRemoteSync()

        assertEquals(1, git.log(Path.of("blocks", "one.md")).size)
        assertEquals(RemoteSyncStatus.FAILED, git.lastRemoteSync?.status)
        assertTrue(git.lastRemoteSync?.message?.isNotBlank() == true)
        assertEquals(git.lastRemoteSync?.message, git.lastRemoteSync?.failed)
    }

    @Test
    fun `waiting for a slow automatic sync is a non-fatal timeout`() {
        val unfinished = FutureTask { Unit }

        assertFalse(unfinished.completesWithin(timeoutMillis = 1))
    }

    @Test
    fun credentialsAreRejectedInTheRepositoryUrl() {
        val git = LibraryGit(root)
        git.init()
        commitFile(git, "blocks/one.md", "one")

        assertFailsWith<IllegalArgumentException> {
            git.syncRemote(RemoteGitConfig("https://user:secret@example.com/repository.git"))
        }
    }

    @Test
    fun historyCountsEveryCommitAndDatesTheNewest() {
        val git = LibraryGit(root)
        git.init()

        // A repository without a commit is a fresh library, not a broken one.
        assertEquals(LibraryHistory(0, null), git.history())

        commitFile(git, "blocks/one.md", "one")
        commitFile(git, "blocks/two.md", "two")

        val history = git.history()
        assertEquals(2, history.commits)
        assertNotNull(history.lastCommitEpochMillis)
    }

    @Test
    fun historyOfANonRepositoryIsEmptyRatherThanAFailure() {
        assertEquals(LibraryHistory(0, null), LibraryGit(root).history())
    }

    private fun config() = RemoteGitConfig(remote.toUri().toString(), branch = "main")

    private fun cloneRemote() {
        Git.cloneRepository()
            .setURI(remote.toUri().toString())
            .setBranch("refs/heads/main")
            .setDirectory(other.toFile())
            .call()
            .close()
    }

    private fun commitBlockInOther(id: String, version: Int, content: String) {
        val path = other.resolve("blocks/$id.md")
        path.parent.createDirectories()
        path.writeText(BlockFile.serialize(Block(id, id, type = BlockType.RULE, version = version, content = content)))
        Git.open(other.toFile()).use { git ->
            git.add().addFilepattern("blocks/$id.md").call()
            git.commit().setMessage("Remote $id v$version $content").call()
            git.push().call()
        }
    }

    private fun deleteBlockInOther(id: String) {
        Files.delete(other.resolve("blocks/$id.md"))
        Git.open(other.toFile()).use { git ->
            git.rm().addFilepattern("blocks/$id.md").call()
            git.commit().setMessage("Delete remote $id").call()
            git.push().call()
        }
    }

    private fun commitBlock(git: LibraryGit, id: String, version: Int, content: String) {
        commitFile(git, "blocks/$id.md", BlockFile.serialize(Block(id, id, type = BlockType.RULE, version = version, content = content)))
    }

    private fun commitFile(git: LibraryGit, relative: String, content: String) = commitFile(git, root, relative, content)

    private fun commitFile(git: LibraryGit, directory: Path, relative: String, content: String) {
        val path = directory.resolve(relative)
        path.parent.createDirectories()
        path.writeText(content)
        git.commit("Save $relative", Path.of(relative))
    }
}
