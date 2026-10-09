package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.GitSkillSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * End-to-end remote-sync scenarios. Each test starts from a local bare remote and two independent
 * working clones, so JGit fetch, merge and push execute exactly as they do between two devices.
 */
class LibraryGitSyncIntegrationTest {

    private val workspaces = mutableListOf<SyncWorkspace>()

    @AfterTest
    fun tearDown() {
        workspaces.forEach { deleteFixtureTree(it.root) }
    }

    @Test
    fun `fast-forward down imports a commit from the other working copy`() {
        val workspace = workspace(block("one", 1, "initial"))
        workspace.second.saveBlock("one", 2, "from second")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.syncDown(workspace.config)

        assertEquals("from second", workspace.first.block("one").content)
        assertEquals(1, result.behind)
        assertEquals(0, result.ahead)
        assertFalse(result.merged)
    }

    @Test
    fun `fast-forward up publishes a local commit to the bare remote`() {
        val workspace = workspace(block("one", 1, "initial"))
        workspace.first.saveBlock("one", 2, "from first")

        val result = workspace.first.git.sync(workspace.config)

        assertEquals(RemoteSyncStatus.PUSHED, result.status)
        assertEquals(0, result.behind)
        assertEquals(1, result.ahead)
        assertEquals("from first", workspace.remoteBlock("one").content)
    }

    @Test
    fun `LF and CRLF devices exchange changes without formatting revisions or repeated sync work`() {
        val workspace = workspace(block("one", 1, "initial\n"))
        val crlfGit = LibraryGit(workspace.second.root, lineEnding = { LineEnding.CRLF })
        val crlfRepository = LibraryRepository(workspace.second.root, crlfGit)
        crlfRepository.init()
        crlfRepository.saveBlock(crlfRepository.loadBlock("one")!!.copy(content = "from CRLF device\r\n"))
        crlfGit.sync(workspace.config)
        workspace.first.git.sync(workspace.config)
        assertEquals("from CRLF device\n", workspace.first.block("one").content)
        assertEquals(2, workspace.first.block("one").version)

        workspace.first.saveBlock("one", 3, "from LF device\n")
        workspace.first.git.sync(workspace.config)
        crlfGit.sync(workspace.config)

        assertEquals(3, crlfRepository.loadBlock("one")!!.version)
        assertContains(workspace.second.path("blocks/one.md").readText(), "\r\n")
        assertFalse('\r' in workspace.first.path("blocks/one.md").readText())
        repeat(2) {
            assertEquals(RemoteSyncStatus.UP_TO_DATE, crlfGit.sync(workspace.config).status)
            assertEquals(RemoteSyncStatus.UP_TO_DATE, workspace.first.git.sync(workspace.config).status)
        }
        Git.open(workspace.second.root.toFile()).use { assertTrue(it.status().call().isClean) }
    }

    @Test
    fun `legacy CRLF checkout accepts incoming changes and stays clean on repeated sync`() {
        val workspace = workspace(block("one", 1, "initial\nsecond line\n"))
        val path = workspace.first.path("blocks/one.md")
        workspace.first.checkoutWithCrlf("blocks/one.md")
        assertContains(path.readText(), "\r\n")
        workspace.second.saveBlock("one", 2, "from second\n")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        assertEquals("from second\n", workspace.first.block("one").content)
        assertTrue(result.conflicts.isEmpty())
        assertEquals(RemoteSyncStatus.UP_TO_DATE, workspace.first.git.sync(workspace.config).status)
        Git.open(workspace.first.root.toFile()).preserveFileBytes().use { git ->
            assertTrue(git.status().call().isClean)
        }
    }

    @Test
    fun `legacy CRLF checkout still resolves concurrent committed block edits`() {
        val workspace = workspace(block("one", 1, "initial\n"))
        workspace.first.saveBlock("one", 2, "from first\n")
        workspace.first.checkoutWithCrlf("blocks/one.md")
        workspace.second.saveBlock("one", 2, "from second\n")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        assertEquals("from first\n", workspace.first.block("one").content)
        assertEquals(3, workspace.first.block("one").version)
        assertEquals(listOf(RemoteSyncConflict("one", RemoteSyncConflictKind.BLOCK)), result.conflicts)
        assertEquals(RemoteSyncStatus.UP_TO_DATE, workspace.first.git.sync(workspace.config).status)
    }

    @Test
    fun `legacy normalization preserves committed CRLF binary files and staged content edits`() {
        val workspace = workspace(block("one", 1, "initial\n"))
        workspace.first.write("skills/raw/files/crlf.txt", "intentional\r\n")
        workspace.first.write("skills/raw/files/binary.dat", "\u0000binary\n")
        workspace.first.git.sync(workspace.config)
        workspace.second.git.sync(workspace.config)
        val path = workspace.first.path("blocks/one.md")
        val edited = path.readText().replace("initial", "staged edit").replace("\n", "\r\n")
        path.writeText(edited)
        Git.open(workspace.first.root.toFile()).preserveFileBytes().use { git ->
            git.add().addFilepattern("blocks/one.md").call()
        }
        workspace.second.saveBlock("one", 2, "from second\n")
        workspace.second.git.sync(workspace.config)

        assertFailsWith<org.eclipse.jgit.api.errors.CheckoutConflictException> {
            workspace.first.git.sync(workspace.config)
        }

        assertEquals(edited, path.readText())
        assertEquals("intentional\r\n", workspace.first.path("skills/raw/files/crlf.txt").readText())
        assertEquals("\u0000binary\n", workspace.first.path("skills/raw/files/binary.dat").readText())
        Git.open(workspace.first.root.toFile()).preserveFileBytes().use { git ->
            assertEquals(setOf("blocks/one.md"), git.status().call().changed)
        }
    }

    @Test
    fun `incoming changes do not overwrite an uncommitted content edit with CRLF`() {
        val workspace = workspace(block("one", 1, "initial\n"))
        val path = workspace.first.path("blocks/one.md")
        val edited = path.readText().replace("initial", "unsaved edit").replace("\n", "\r\n")
        path.writeText(edited)
        workspace.second.saveBlock("one", 2, "from second\n")
        workspace.second.git.sync(workspace.config)

        assertFailsWith<org.eclipse.jgit.api.errors.CheckoutConflictException> {
            workspace.first.git.sync(workspace.config)
        }

        assertEquals(edited, path.readText())
    }

    @Test
    fun `disjoint changes merge without object conflicts`() {
        val workspace = workspace(block("base", 1, "base"))
        workspace.first.saveBlock("local", 1, "first only")
        workspace.second.saveBlock("remote", 1, "second only")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        assertTrue(result.merged)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("first only", workspace.first.block("local").content)
        assertEquals("second only", workspace.first.block("remote").content)
        assertEquals("first only", workspace.remoteBlock("local").content)
        assertEquals("second only", workspace.remoteBlock("remote").content)
    }

    @Test
    fun `same object conflict retains both revisions and makes local current`() {
        val workspace = workspace(block("one", 1, "initial"))
        workspace.first.saveBlock("one", 2, "local version")
        workspace.second.saveBlock("one", 2, "remote version")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        assertEquals(block("one", 3, "local version"), workspace.first.block("one"))
        assertEquals(listOf(RemoteSyncConflict("one", RemoteSyncConflictKind.BLOCK)), result.conflicts)
        assertTrue(workspace.first.blockHistory("one").any { it.version == 2 && it.content == "local version" })
        assertTrue(workspace.first.blockHistory("one").any { it.version == 2 && it.content == "remote version" })
    }

    @Test
    fun `delete versus modify restores the modified object from either working copy`() {
        val workspace = workspace(block("local-delete", 1, "initial"), block("remote-delete", 1, "initial"))
        workspace.first.deleteBlock("local-delete")
        workspace.first.saveBlock("remote-delete", 2, "local edit")
        workspace.second.saveBlock("local-delete", 2, "remote edit")
        workspace.second.deleteBlock("remote-delete")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        assertEquals("remote edit", workspace.first.block("local-delete").content)
        assertEquals("local edit", workspace.first.block("remote-delete").content)
        assertEquals(
            listOf(
                RemoteSyncConflict("local-delete", RemoteSyncConflictKind.BLOCK, restored = true),
                RemoteSyncConflict("remote-delete", RemoteSyncConflictKind.BLOCK, restored = true),
            ),
            result.conflicts,
        )
    }

    @Test
    fun `concurrent block and group versions are renumbered after merge`() {
        val workspace = workspace(
            block("one", 1, "initial"),
            groups = listOf(Group("review", "Review", version = 1, blockIds = listOf("one"))),
        )
        workspace.first.saveBlock("one", 2, "local")
        workspace.first.saveGroup(Group("review", "Review", version = 2, blockIds = listOf("one"), skillIds = listOf("local-skill")))
        workspace.second.saveBlock("one", 2, "remote")
        workspace.second.saveGroup(Group("review", "Remote review", version = 2, blockIds = listOf("one")))
        workspace.second.git.sync(workspace.config)

        workspace.first.git.sync(workspace.config)

        assertEquals(3, workspace.first.block("one").version)
        assertEquals(3, workspace.first.group("review").version)
        assertEquals("Review", workspace.first.group("review").name)
    }

    @Test
    fun `concurrent profile versions are renumbered after merge`() {
        val workspace = workspace(block("one", 1, "initial"))
        workspace.first.saveProfile(Profile("review", "Review", blockIds = listOf("one")))
        workspace.first.git.sync(workspace.config)
        workspace.second.git.sync(workspace.config)

        workspace.first.saveProfile(Profile("review", "Review", blockIds = listOf("one", "local"), version = 2))
        workspace.second.saveProfile(Profile("review", "Remote review", blockIds = listOf("one", "remote"), version = 2))
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        assertEquals(3, workspace.first.profile("review").version)
        assertEquals("Review", workspace.first.profile("review").name)
        assertEquals(listOf(RemoteSyncConflict("review", RemoteSyncConflictKind.PROFILE)), result.conflicts)
    }

    @Test
    fun `all group is rebuilt from merged objects instead of text merged`() {
        val workspace = workspace(
            block("base", 1, "base"),
            groups = listOf(Group(ALL_GROUP_ID, ALL_GROUP_ID, version = 1, blockIds = listOf("base"))),
        )
        workspace.first.saveBlock("local", 1, "local")
        workspace.second.saveBlock("remote", 1, "remote")
        workspace.second.git.sync(workspace.config)

        workspace.first.git.sync(workspace.config)

        assertEquals(
            Group(ALL_GROUP_ID, ALL_GROUP_ID, version = 2, blockIds = listOf("base", "local", "remote")),
            workspace.first.group(ALL_GROUP_ID),
        )
    }

    @Test
    fun `unrelated histories require explicit import merge`() {
        val workspace = workspace(block("remote", 1, "remote"))
        val unrelatedRoot = workspace.root.resolve("unrelated")
        val unrelated = WorkingCopy(unrelatedRoot, LibraryGit(unrelatedRoot))
        unrelated.git.init()
        unrelated.saveBlock("local", 1, "local")

        assertFailsWith<UnrelatedLibraryHistories> { unrelated.git.sync(workspace.config) }
        unrelated.git.sync(workspace.config, allowUnrelatedHistories = true)

        assertTrue(unrelated.path("blocks/local.md").exists())
        assertTrue(unrelated.path("blocks/remote.md").exists())
    }

    @Test
    fun `rejected push syncs down and retries successfully`() {
        val workspace = workspace(block("base", 1, "base"))
        workspace.first.saveBlock("local", 1, "local")
        workspace.second.saveBlock("remote-before-fetch", 1, "remote")
        workspace.second.git.sync(workspace.config)
        workspace.second.saveBlock("remote-race", 1, "race")

        val fetched = CountDownLatch(1)
        val pushed = CountDownLatch(1)
        val racingPush = thread(name = "remote-race") {
            val trackingRef = workspace.first.path(".git/refs/remotes/ruleblend-sync/main")
            while (!trackingRef.exists()) Thread.yield()
            fetched.countDown()
            workspace.second.git.sync(workspace.config)
            pushed.countDown()
        }

        val result = workspace.first.git.sync(workspace.config)

        assertTrue(fetched.await(5, TimeUnit.SECONDS))
        racingPush.join(5_000)
        assertTrue(pushed.await(5, TimeUnit.SECONDS))
        assertEquals("local", workspace.remoteBlock("local").content)
        assertEquals("remote", workspace.remoteBlock("remote-before-fetch").content)
        assertEquals("race", workspace.remoteBlock("remote-race").content)
        assertTrue(result.merged)
        assertEquals(1, result.behind)
    }

    @Test
    fun `delete versus modify keeps a skill whole instead of only its changed file`() {
        val workspace = workspace(block("base", 1, "base"))
        workspace.first.saveSkill("local-delete", "# Local delete\n")
        workspace.first.saveSkill("remote-delete", "# Remote delete\n")
        workspace.first.git.sync(workspace.config)
        workspace.second.git.sync(workspace.config)

        workspace.first.deleteSkill("local-delete")
        workspace.first.saveSkill("remote-delete", "# Local edit\n")
        workspace.second.saveSkill("local-delete", "# Remote edit\n")
        workspace.second.deleteSkill("remote-delete")
        workspace.second.git.sync(workspace.config)

        val result = workspace.first.git.sync(workspace.config)

        // Git alone would keep only the one file each side happened to touch, leaving a directory
        // without meta.yaml that the library no longer reads as a skill.
        assertEquals(listOf("files/SKILL.md", "files/notes.md", "meta.yaml"), workspace.first.skillFiles("local-delete"))
        assertEquals(listOf("files/SKILL.md", "files/notes.md", "meta.yaml"), workspace.first.skillFiles("remote-delete"))
        assertEquals("# Remote edit\n", workspace.first.path("skills/local-delete/files/SKILL.md").readText())
        assertEquals("# Local edit\n", workspace.first.path("skills/remote-delete/files/SKILL.md").readText())
        assertEquals(
            listOf(
                RemoteSyncConflict("local-delete", RemoteSyncConflictKind.SKILL, restored = true),
                RemoteSyncConflict("remote-delete", RemoteSyncConflictKind.SKILL, restored = true),
            ),
            result.conflicts,
        )
    }

    @Test
    fun `a merge it refuses to resolve leaves the library writable`() {
        val workspace = workspace(block("base", 1, "base"))
        workspace.first.write("notes.txt", "first\n")
        workspace.second.write("notes.txt", "second\n")
        workspace.second.git.sync(workspace.config)

        assertFailsWith<IllegalStateException> { workspace.first.git.sync(workspace.config) }

        // An abandoned merge would make git reject every later save and every later sync, so the
        // library would stay stuck until someone ran git by hand.
        workspace.first.saveBlock("after", 1, "after")
        assertEquals("after", workspace.first.block("after").content)
        workspace.first.path("notes.txt").deleteExisting()
        workspace.first.git.commit("Drop notes", Path.of("notes.txt"))
        workspace.first.git.sync(workspace.config)
        assertEquals("base", workspace.first.block("base").content)
    }

    @Test
    fun `the rebuilt all group keeps the order the library itself writes`() {
        val workspace = workspace(block("base", 1, "base"))
        workspace.first.saveBlock("aaa", 1, "x", name = "Zulu")
        workspace.first.git.sync(workspace.config)
        workspace.second.saveBlock("zzz", 1, "y", name = "Alpha")

        workspace.second.git.sync(workspace.config)

        // Two orderings would make each device renumber the other's `all` on every sync, and every
        // target would keep reinstalling a group whose membership never changed.
        val rebuilt = workspace.second.group(ALL_GROUP_ID)
        LibraryRepository(workspace.second.root, workspace.second.git).syncAllGroup()
        assertEquals(rebuilt, workspace.second.group(ALL_GROUP_ID))
        assertEquals(listOf("zzz", "aaa", "base"), rebuilt.blockIds)
    }

    @Test
    fun `skill executable metadata survives synchronization in both directions`() {
        val workspace = workspace(block("base", 1, "base"))
        val first = LibraryRepository(workspace.first.root, workspace.first.git)
        val second = LibraryRepository(workspace.second.root, workspace.second.git)
        val skill = Skill(
            "review", "review", content = "# Review\n",
            source = GitSkillSource("https://example.com/skills", "revision", "skills/review"),
        )
        val snapshot = SkillSnapshot(skill, listOf(
            SkillFile("SKILL.md", skill.content.encodeToByteArray()),
            SkillFile("scripts/check.sh", "#!/bin/sh\nexit 0\n".encodeToByteArray(), executable = true),
            SkillFile("assets/data.bin", byteArrayOf(0, -1, 42)),
            SkillFile("notes.txt", "Keep CRLF\r\n".encodeToByteArray()),
        ))
        first.writeSkill(snapshot)
        workspace.first.git.sync(workspace.config)
        workspace.second.git.syncDown(workspace.config)
        assertEquals(snapshot.files.toSet(), second.loadSkillSnapshot(skill.id)?.files?.toSet())

        // Model a checkout that cannot carry mode bits; portable metadata remains authoritative.
        setSkillExecutable(workspace.second.path("skills/review/files/scripts/check.sh"), false)
        val fork = second.forkSkill(skill.id, "review-changed", "review-changed")
        setSkillExecutable(workspace.second.path("skills/review/files/scripts/check.sh"), true)
        workspace.second.git.sync(workspace.config)
        workspace.first.git.syncDown(workspace.config)
        assertEquals(
            second.loadSkillSnapshot(fork.id)?.files?.toSet(),
            first.loadSkillSnapshot(fork.id)?.files?.toSet(),
        )
        assertEquals(listOf("scripts/check.sh"), first.loadSkillSnapshot(fork.id)?.files?.filter { it.executable }?.map { it.path })
    }

    private fun workspace(vararg blocks: Block, groups: List<Group> = emptyList()): SyncWorkspace =
        SyncWorkspace(Files.createTempDirectory("ruleblend-sync-integration"), blocks.toList(), groups).also(workspaces::add)
}

private class SyncWorkspace(
    val root: Path,
    blocks: List<Block>,
    groups: List<Group>,
) {
    val remote = root.resolve("remote.git")
    val config = RemoteGitConfig(remote.toUri().toString(), branch = "main")
    val first = root.resolve("first").cloneFrom(remote, config, blocks, groups)
    val second = root.resolve("second").cloneFrom(remote, config)

    fun remoteBlock(id: String): Block = Git.open(remote.toFile()).use { bare ->
        val head = checkNotNull(bare.repository.resolve("refs/heads/main"))
        readBlockAt(bare, head, "blocks/$id.md", id)
    }
}

private fun Path.cloneFrom(remote: Path, config: RemoteGitConfig, blocks: List<Block> = emptyList(), groups: List<Group> = emptyList()): WorkingCopy {
    if (!remote.exists()) Git.init().setBare(true).setDirectory(remote.toFile()).call().close()
    if (blocks.isNotEmpty() || groups.isNotEmpty()) {
        val seedRoot = remote.parent.resolve("seed")
        val seed = WorkingCopy(seedRoot, LibraryGit(seedRoot))
        seed.git.init()
        blocks.forEach { seed.saveBlock(it.id, it.version, it.content) }
        groups.forEach(seed::saveGroup)
        seed.git.sync(config)
    }
    Git.cloneRepository()
        .setURI(remote.toUri().toString())
        .setBranch("refs/heads/main")
        .setDirectory(toFile())
        .setNoCheckout(true)
        .call()
        .use { it.checkoutPortableTree() }
    return WorkingCopy(this, LibraryGit(this))
}

private class WorkingCopy(val root: Path, val git: LibraryGit) {
    fun path(relative: String): Path = root.resolve(relative)

    fun checkoutWithCrlf(relative: String) {
        Git.open(root.toFile()).use { git ->
            git.repository.config.setBoolean("core", null, "autocrlf", true)
            git.repository.config.save()
            path(relative).deleteExisting()
            git.checkout().addPath(relative).call()
        }
    }

    fun block(id: String): Block = BlockFile.parse(id, path("blocks/$id.md").readText())

    fun group(id: String): Group = Yaml.default.decodeFromString(Group.serializer(), path("groups/$id.yaml").readText())

    fun profile(id: String): Profile =
        Yaml.default.decodeFromString(Profile.serializer(), path("profiles/$id.yaml").readText())

    fun saveBlock(id: String, version: Int, content: String, name: String = id) {
        val path = path("blocks/$id.md")
        path.parent.createDirectories()
        path.writeText(BlockFile.serialize(block(id, version, content).copy(name = name)))
        git.commit("Save block $id", Path.of("blocks", "$id.md"))
    }

    fun write(relative: String, text: String) {
        val path = path(relative)
        path.parent.createDirectories()
        path.writeText(text)
        git.commit("Write $relative", Path.of(relative))
    }

    /** A skill is a directory, so every helper here writes the whole object, never one of its files. */
    fun saveSkill(id: String, content: String) {
        val directory = path("skills/$id")
        directory.resolve("files").createDirectories()
        directory.resolve("meta.yaml").writeText("name: \"$id\"\n")
        directory.resolve("files/SKILL.md").writeText(content)
        directory.resolve("files/notes.md").writeText("notes\n")
        git.commit("Save skill $id", Path.of("skills", id))
    }

    fun deleteSkill(id: String) {
        path("skills/$id").toFile().deleteRecursively()
        git.commit("Delete skill $id", Path.of("skills", id))
    }

    fun skillFiles(id: String): List<String> {
        val directory = path("skills/$id")
        return directory.toFile().walkTopDown().filter { it.isFile }
            .map { directory.relativize(it.toPath()).joinToString("/") }
            .sorted()
            .toList()
    }

    fun saveGroup(group: Group) {
        val path = path("groups/${group.id}.yaml")
        path.parent.createDirectories()
        path.writeText(Yaml.default.encodeToString(Group.serializer(), group) + "\n")
        git.commit("Save group ${group.id}", Path.of("groups", "${group.id}.yaml"))
    }

    fun saveProfile(profile: Profile) {
        val path = path("profiles/${profile.id}.yaml")
        path.parent.createDirectories()
        path.writeText(Yaml.default.encodeToString(Profile.serializer(), profile) + "\n")
        git.commit("Save profile ${profile.id}", Path.of("profiles", "${profile.id}.yaml"))
    }

    fun deleteBlock(id: String) {
        Files.delete(path("blocks/$id.md"))
        git.commit("Delete block $id", Path.of("blocks", "$id.md"))
    }

    fun blockHistory(id: String): List<Block> = Git.open(root.toFile()).use { repository ->
        repository.log().addPath("blocks/$id.md").call().map { commit ->
            readBlockAt(repository, commit.id, "blocks/$id.md", id)
        }.toList()
    }
}

private fun block(id: String, version: Int, content: String): Block =
    Block(id, id, version = version, type = BlockType.RULE, content = content)

private fun readBlockAt(git: Git, commit: org.eclipse.jgit.lib.ObjectId, path: String, id: String): Block =
    RevWalk(git.repository).use { walk ->
        val tree = walk.parseCommit(commit).tree
        TreeWalk.forPath(git.repository, path, tree).use { entry ->
            checkNotNull(entry) { "Missing $path at $commit" }
            BlockFile.parse(id, git.repository.open(entry.getObjectId(0)).bytes.toString(Charsets.UTF_8))
        }
    }
