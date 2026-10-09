package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {

    private lateinit var root: Path
    private lateinit var repo: LibraryRepository

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("ruleblend-test")
        repo = LibraryRepository(root)
        repo.init()
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(root)
    }

    private val block = Block(
        id = "git-no-commit",
        name = "Git: no commit",
        description = "Never commit without asking",
        content = "- Do not commit unless asked.\n",
    )

    @Test
    fun `object paths reject ids outside their library directory`() {
        for (id in listOf("../outside", "../../outside", "/tmp/outside", "a/b", "a\\b", "", ".", "..")) {
            assertFailsWith<IllegalArgumentException>(id) { repo.saveBlock(block.copy(id = id)) }
            assertFailsWith<IllegalArgumentException>(id) { repo.saveGroup(Group(id, "Unsafe")) }
            assertFailsWith<IllegalArgumentException>(id) { repo.saveProfile(Profile(id, "Unsafe")) }
            assertFailsWith<IllegalArgumentException>(id) { repo.loadBlock(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.deleteBlock(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.loadGroup(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.deleteGroup(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.loadProfile(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.deleteProfile(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.loadSkill(id) }
            assertFailsWith<IllegalArgumentException>(id) { repo.deleteSkill(id) }
        }
    }

    @Test
    fun `block writes reject reserved redirect ids`() {
        for (id in listOf("ruleblend-import", "kitbash-import")) {
            assertFailsWith<IllegalArgumentException> { repo.saveBlock(block.copy(id = id)) }
            assertFailsWith<IllegalArgumentException> { repo.writeBlock(block.copy(id = id)) }
            assertNull(repo.loadBlock(id))
        }
    }

    @Test
    fun `block round trip through disk`() {
        val saved = repo.saveBlock(block)
        assertEquals(block, saved)
        assertEquals(block, repo.loadBlock(block.id))
        assertEquals(listOf(block), repo.listBlocks())
    }

    @Test
    fun `version increments only on content change`() {
        repo.saveBlock(block)
        val renamed = repo.saveBlock(block.copy(name = "Git rules"))
        assertEquals(1, renamed.version)

        val edited = repo.saveBlock(renamed.copy(content = "- Ask first.\n"))
        assertEquals(2, edited.version)
        assertEquals(2, repo.loadBlock(block.id)?.version)
    }

    @Test
    fun `subagent frontmatter is persisted and increments its definition version`() {
        val subagent = Block(
            id = "code-reviewer",
            name = "Code reviewer",
            type = BlockType.SUBAGENT,
            variants = mapOf("claude-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra"))),
            content = "# Review\n",
        )
        val saved = repo.saveBlock(subagent)

        val updated = repo.saveBlock(
            saved.copy(
                description = "Reviews Kotlin changes",
                variants = mapOf("claude-code" to SubagentVariant(mapOf("model" to "o3"))),
            ),
        )

        assertEquals(2, updated.version)
        assertEquals(updated, repo.loadBlock(subagent.id))
        assertEquals(listOf(updated), repo.listBlocks())
    }

    @Test
    fun `delete removes block`() {
        repo.saveBlock(block)
        repo.deleteBlock(block.id)
        assertNull(repo.loadBlock(block.id))
        assertTrue(repo.listBlocks().isEmpty())
    }

    @Test
    fun `group round trip through disk`() {
        val group = Group("ios-projects", "iOS projects", "Blocks for iOS repos", listOf(block.id))
        repo.saveGroup(group)
        assertEquals(group, repo.loadGroup(group.id))
        assertEquals(listOf(group), repo.listGroups())

        repo.deleteGroup(group.id)
        assertNull(repo.loadGroup(group.id))
    }

    @Test
    fun `legacy group without skill ids remains readable`() {
        root.resolve("groups/legacy.yaml").writeText(
            "id: legacy\nname: Legacy\ndescription: ''\nblockIds:\n- git-no-commit\nversion: 1\n",
        )

        val group = repo.loadGroup("legacy")

        assertEquals(listOf("git-no-commit"), group?.blockIds)
        assertEquals(emptyList(), group?.skillIds)
    }

    @Test
    fun `group version increments only on membership change`() {
        val group = Group("ios-projects", "iOS projects", "Blocks for iOS repos", listOf(block.id))
        repo.saveGroup(group)
        assertEquals(1, repo.loadGroup(group.id)?.version)

        repo.saveGroup(group.copy(description = "Renamed description"))
        assertEquals(1, repo.loadGroup(group.id)?.version)

        repo.saveGroup(group.copy(blockIds = group.blockIds + "another-block"))
        assertEquals(2, repo.loadGroup(group.id)?.version)

        repo.saveGroup(requireNotNull(repo.loadGroup(group.id)).copy(skillIds = listOf("review")))
        assertEquals(3, repo.loadGroup(group.id)?.version)
    }

    @Test
    fun `profile round trips and can be deleted`() {
        val profile = Profile(
            id = "review",
            name = "Review",
            description = "Review mode",
            blockIds = listOf("kotlin-style"),
            skillIds = listOf("review-skill"),
            subagentIds = listOf("code-reviewer"),
            groupIds = listOf("shared"),
        )

        assertEquals(profile, repo.saveProfile(profile))
        assertEquals(profile, repo.loadProfile(profile.id))
        assertEquals(listOf(profile), repo.listProfiles())

        repo.deleteProfile(profile.id)

        assertNull(repo.loadProfile(profile.id))
        assertTrue(repo.listProfiles().isEmpty())
    }

    @Test
    fun `profile version increments only on membership change`() {
        val profile = Profile(id = "review", name = "Review", blockIds = listOf("kotlin-style"))
        repo.saveProfile(profile)

        val renamed = repo.saveProfile(profile.copy(name = "Code review", description = "Review mode"))
        val withBlock = repo.saveProfile(renamed.copy(blockIds = listOf("kotlin-style", "swift-style")))
        val withSkill = repo.saveProfile(withBlock.copy(skillIds = listOf("review-skill")))
        val withSubagent = repo.saveProfile(withSkill.copy(subagentIds = listOf("code-reviewer")))
        val withGroup = repo.saveProfile(withSubagent.copy(groupIds = listOf("shared")))

        assertEquals(1, renamed.version)
        assertEquals(2, withBlock.version)
        assertEquals(3, withSkill.version)
        assertEquals(4, withSubagent.version)
        assertEquals(5, withGroup.version)
        assertEquals(withGroup, repo.loadProfile(profile.id))
    }

    @Test
    fun `writeProfile preserves an imported version`() {
        val profile = Profile(id = "review", name = "Review", blockIds = listOf("a"), version = 7)

        repo.writeProfile(profile)

        assertEquals(profile, repo.loadProfile(profile.id))
    }

    private class FailingGit(root: Path) : LibraryGit(root) {
        override fun commit(message: String, relativePath: Path) = throw java.io.IOException("simulated commit failure")
    }

    @Test
    fun `a failed commit leaves no orphan block file on first save`() {
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> { brokenRepo.saveBlock(block) }

        assertNull(brokenRepo.loadBlock(block.id))
        assertTrue(brokenRepo.listBlocks().isEmpty())
    }

    @Test
    fun `a failed commit restores the previous version on disk`() {
        repo.saveBlock(block)
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> { brokenRepo.saveBlock(block.copy(content = "- Ask first.\n")) }

        assertEquals(block, repo.loadBlock(block.id))
    }

    @Test
    fun `a failed commit does not delete a block`() {
        repo.saveBlock(block)
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> { brokenRepo.deleteBlock(block.id) }

        assertEquals(block, repo.loadBlock(block.id))
    }

    @Test
    fun `a failed commit leaves no orphan group file on first save`() {
        val group = Group("ios-projects", "iOS projects", "Blocks for iOS repos", listOf(block.id))
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> { brokenRepo.saveGroup(group) }

        assertNull(brokenRepo.loadGroup(group.id))
    }

    @Test
    fun `a failed commit restores the previous group on disk`() {
        val group = Group("ios-projects", "iOS projects", "Blocks for iOS repos", listOf(block.id))
        repo.saveGroup(group)
        val stored = requireNotNull(repo.loadGroup(group.id))
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> {
            brokenRepo.saveGroup(stored.copy(blockIds = stored.blockIds + "another-block"))
        }

        assertEquals(stored, repo.loadGroup(group.id))
    }

    @Test
    fun `a failed commit does not delete a group`() {
        val group = Group("ios-projects", "iOS projects", "Blocks for iOS repos", listOf(block.id))
        repo.saveGroup(group)
        val stored = requireNotNull(repo.loadGroup(group.id))
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> { brokenRepo.deleteGroup(group.id) }

        assertEquals(stored, repo.loadGroup(group.id))
    }

    @Test
    fun `a failed commit restores the previous profile`() {
        val profile = repo.saveProfile(Profile(id = "review", name = "Review", blockIds = listOf("a")))
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> {
            brokenRepo.saveProfile(profile.copy(blockIds = listOf("b")))
        }

        assertEquals(profile, repo.loadProfile(profile.id))
    }

    @Test
    fun `concurrent saves never lose a version bump`() {
        val writers = 4
        val savesPerWriter = 10
        repo.saveBlock(block)

        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until writers).map { writer ->
            Thread {
                start.await()
                repeat(savesPerWriter) { save ->
                    repo.saveBlock(block.copy(content = "- Edit $writer-$save.\n"))
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        // Every save carries content no other writer used, so each one is a real change: the read-
        // modify-write in saveBlock must have bumped the version exactly once per save.
        val expected = 1 + writers * savesPerWriter
        assertEquals(expected, repo.loadBlock(block.id)?.version)
        assertEquals(expected, LibraryGit(root).log(Path.of("blocks", "${block.id}.md")).size)
    }

    @Test
    fun `syncAllGroup creates the reserved group from every block and skill`() {
        repo.saveBlock(block)
        val skill = Skill("review", "review", content = "# Review\n")
        repo.createSkill(skill)

        repo.syncAllGroup()

        assertEquals(listOf(block.id), repo.loadGroup(ALL_GROUP_ID)?.blockIds)
        assertEquals(listOf(skill.id), repo.loadGroup(ALL_GROUP_ID)?.skillIds)
    }

    @Test
    fun `syncAllGroup never includes profiles`() {
        repo.saveProfile(Profile(id = "review", name = "Review", blockIds = listOf(block.id)))

        repo.syncAllGroup()

        val all = requireNotNull(repo.loadGroup(ALL_GROUP_ID))
        assertTrue(all.blockIds.isEmpty())
        assertTrue(all.skillIds.isEmpty())
    }

    @Test
    fun `syncAllGroup is a no-op when membership already matches`() {
        repo.saveBlock(block)
        repo.syncAllGroup()
        val version = repo.loadGroup(ALL_GROUP_ID)?.version
        val commits = LibraryGit(root).log(Path.of("groups", "${ALL_GROUP_ID}.yaml")).size

        repo.syncAllGroup()

        assertEquals(version, repo.loadGroup(ALL_GROUP_ID)?.version)
        assertEquals(commits, LibraryGit(root).log(Path.of("groups", "${ALL_GROUP_ID}.yaml")).size)
    }

    @Test
    fun `each save produces a commit`() {
        repo.saveBlock(block)
        repo.saveBlock(block.copy(content = "- Ask first.\n"))

        val history = LibraryGit(root).log(Path.of("blocks", "${block.id}.md"))
        assertEquals(2, history.size)
        assertEquals("Save block ${block.id} v2", history.first().fullMessage)
    }

    @Test
    fun `imported skill round trips with its complete file tree`() {
        val skill = Skill(
            id = "review",
            name = "Code review",
            version = "2.4.0",
            content = "# Review\n",
            source = GitSkillSource("https://example.com/skills.git", "abc123", "skills/review"),
        )
        val saved = repo.writeSkill(
            SkillSnapshot(
                skill,
                listOf(
                    SkillFile("SKILL.md", skill.content.encodeToByteArray()),
                    SkillFile("references/checklist.md", "Check tests.\n".encodeToByteArray()),
                ),
            ),
        )

        assertEquals(skill, saved)
        assertEquals(listOf(skill), repo.listSkills())
        assertEquals("Check tests.\n", root.resolve("skills/review/files/references/checklist.md").toFile().readText())
    }

    @Test
    fun `local skill can be created and edited`() {
        val created = repo.createSkill(Skill("review", "review", description = "Use for reviews", content = "# Review\n"))
        val edited = repo.saveSkill(created.copy(content = "# Safe review\n"))

        assertNull(edited.source)
        assertEquals("2", edited.version)
        assertEquals("# Safe review\n", repo.loadSkill("review")?.content)
    }

    @Test
    fun `updating a skill removes files deleted upstream`() {
        val source = GitSkillSource("https://example.com/skills.git", "abc123", "skills/review")
        repo.writeSkill(
            SkillSnapshot(
                Skill("review", "Review", version = "1.0", content = "old", source = source),
                listOf(SkillFile("SKILL.md", "old".encodeToByteArray()), SkillFile("old.md", byteArrayOf(1))),
            ),
        )

        repo.writeSkill(
            SkillSnapshot(
                Skill("review", "Review", version = "2.0", content = "new", source = source.copy(revision = "def456")),
                listOf(SkillFile("SKILL.md", "new".encodeToByteArray())),
            ),
        )

        assertEquals("new", repo.loadSkill("review")?.content)
        assertTrue(!root.resolve("skills/review/files/old.md").toFile().exists())
        assertEquals(2, LibraryGit(root).log(Path.of("skills", "review")).size)
    }

    @Test
    fun `forked imported skill is local and keeps its sibling files`() {
        val imported = Skill(
            "review",
            "Review",
            version = "2.0",
            content = "upstream",
            source = GitSkillSource("https://example.com/skills.git", "abc", "skills/review"),
        )
        repo.writeSkill(
            SkillSnapshot(
                imported,
                listOf(SkillFile("SKILL.md", "upstream".encodeToByteArray()), SkillFile("script.sh", "run".encodeToByteArray())),
            ),
        )

        val fork = repo.forkSkill("review", "review-changed", "Review changed")
        val edited = repo.saveSkill(fork.copy(content = "local edit"))

        assertNull(edited.source)
        assertEquals(imported.source, edited.forkedFrom)
        assertEquals("2", edited.version)
        assertEquals("upstream", repo.loadSkill("review")?.content)
        assertEquals("run", root.resolve("skills/review-changed/files/script.sh").toFile().readText())
        assertTrue("forkedFrom:" in root.resolve("skills/review-changed/meta.yaml").toFile().readText())
    }

    @Test
    fun `history returns the commits of one object, newest first`() {
        repo.saveBlock(Block("swift-style", "Swift Style", content = "one"))
        repo.saveBlock(Block("swift-style", "Swift Style", content = "two"))
        repo.saveBlock(Block("kotlin-style", "Kotlin Style", content = "other"))

        val history = repo.blockHistory("swift-style")

        assertEquals(listOf("Save block swift-style v2", "Save block swift-style v1"), history.map { it.message })
        assertTrue(history.all { it.id.length == 7 })
        // An object that was never written has no history, and asking for it is not an error.
        assertEquals(emptyList(), repo.blockHistory("missing"))
    }

    @Test
    fun `a revision carries the size of its change and the diff behind it`() {
        repo.saveBlock(Block("swift-style", "Swift Style", content = "one\n"))
        repo.saveBlock(Block("swift-style", "Swift Style", content = "one\ntwo\n"))

        val newest = repo.blockHistory("swift-style").first()

        // The second save added a line and rewrote the version field of the frontmatter.
        assertTrue(newest.added > newest.removed, "an added line must read as added: $newest")
        val diff = repo.blockDiff("swift-style", newest.id)
        assertTrue("+two" in diff, "the diff must hold the line that was added: $diff")
        // A revision that does not exist is a question git cannot answer, not a crash.
        assertEquals("", repo.blockDiff("swift-style", "0000000"))
    }

    @Test
    fun `the first revision of an object diffs against nothing, so all of it reads as added`() {
        repo.saveBlock(Block("swift-style", "Swift Style", content = "one\n"))

        val first = repo.blockHistory("swift-style").single()

        assertTrue(first.added > 0 && first.removed == 0, "a created file has nothing to remove: $first")
        assertTrue("+one" in repo.blockDiff("swift-style", first.id))
    }

    @Test
    fun `a reader of a library skill finds it throughout an update`() {
        // The app's other process (the MCP server) reads skills while the editor saves them.
        val skill = Skill(id = "review", name = "Review", version = "1", content = "body 0")
        repo.writeSkill(SkillSnapshot(skill, listOf(SkillFile("SKILL.md", "body 0".encodeToByteArray()))))
        val watched = root.resolve("skills/review/files/SKILL.md")
        val stop = AtomicBoolean(false)
        val misses = AtomicInteger()
        val reads = AtomicInteger()
        val readerFailure = AtomicReference<Throwable?>()
        val watcher = thread {
            while (!stop.get()) {
                try {
                    Files.readAllBytes(watched)
                    reads.incrementAndGet()
                    // Leave a sharing window between reads, as a real editor does.
                    Thread.sleep(1)
                } catch (_: NoSuchFileException) {
                    misses.incrementAndGet()
                } catch (error: Throwable) {
                    readerFailure.set(error)
                    break
                }
            }
        }
        try {
            repeat(40) { round ->
                val body = "body ${round + 1}"
                repo.writeSkill(SkillSnapshot(skill.copy(version = "${round + 2}", content = body), listOf(SkillFile("SKILL.md", body.encodeToByteArray()))))
            }
        } finally {
            stop.set(true)
            watcher.join()
        }

        assertNull(readerFailure.get(), "the concurrent reader must finish without errors")
        assertTrue(reads.get() > 0, "the reader must have run alongside the saves")
        assertEquals(0, misses.get(), "reads that found no skill out of ${reads.get() + misses.get()}")
    }

    @Test
    fun `a failed skill commit puts the previous tree back`() {
        val skill = Skill(id = "review", name = "Review", version = "1", content = "body")
        repo.writeSkill(SkillSnapshot(skill, listOf(SkillFile("SKILL.md", "body".encodeToByteArray()))))
        val brokenRepo = LibraryRepository(root, FailingGit(root))

        assertFailsWith<java.io.IOException> {
            brokenRepo.writeSkill(
                SkillSnapshot(
                    skill.copy(version = "2", content = "changed"),
                    listOf(SkillFile("SKILL.md", "changed".encodeToByteArray()), SkillFile("notes.md", "new".encodeToByteArray())),
                ),
            )
        }

        assertEquals("body", repo.loadSkill("review")?.content)
        assertTrue(!Files.exists(root.resolve("skills/review/files/notes.md")), "the file the failed write added goes")
        assertTrue(Files.list(root.resolve("skills")).use { entries -> entries.noneMatch { it.fileName.toString().startsWith(".") } })
    }

    @Test
    fun `history of a skill covers every file of its directory`() {
        val skill = Skill(id = "review", name = "Review", version = "1.0", content = "body")
        repo.writeSkill(SkillSnapshot(skill, listOf(SkillFile("SKILL.md", "body".encodeToByteArray()))))

        assertEquals(1, repo.skillHistory("review").size)
        assertEquals(emptyList(), repo.groupHistory("mobile"))
    }
}
