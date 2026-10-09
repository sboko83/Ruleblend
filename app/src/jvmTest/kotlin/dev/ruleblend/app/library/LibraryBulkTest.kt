package dev.ruleblend.app.library

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.InstallItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LibraryBulkTest {

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: LibraryModel
    private val installs = mutableListOf<Pair<Set<String>, List<InstallItem>>>()
    private val removals = mutableListOf<Pair<Set<String>, List<InstallItem>>>()

    private val swift = LibraryObjectKey(LibraryObjectKind.RULE, "swift-style")
    private val kotlin = LibraryObjectKey(LibraryObjectKind.RULE, "kotlin-style")
    private val review = LibraryObjectKey(LibraryObjectKind.SKILL, "code-review")
    private val subagent = LibraryObjectKey(LibraryObjectKind.SUBAGENT, "reviewer")
    private val mobile = LibraryObjectKey(LibraryObjectKind.GROUP, "mobile")

    private val installer = object : LibraryPlaceInstaller {
        override fun places() = listOf(
            LibraryPlaceRef("agent:claude-code", "Claude Code", LibraryPlaceKind.AGENT),
            LibraryPlaceRef("project:/code/atlas", "atlas", LibraryPlaceKind.PROJECT),
        )

        override fun install(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport {
            installs += placeIds to items
            return LibraryInstallReport(written = items.size * placeIds.size)
        }

        override fun remove(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport {
            removals += placeIds to items
            return LibraryInstallReport(written = items.size * placeIds.size)
        }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-library-bulk")
        repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.writeBlock(Block("swift-style", "Swift Style", content = "Use Swift."))
        repository.writeBlock(Block("kotlin-style", "Kotlin Style", content = "Use Kotlin."))
        repository.writeBlock(Block("pdf", "PDF", type = BlockType.MCP, content = "{}"))
        repository.writeBlock(Block("reviewer", "Reviewer", type = BlockType.SUBAGENT, content = "Review changes."))
        repository.writeSkill(
            SkillSnapshot(
                Skill(id = "code-review", name = "code-review", description = "Review", content = "# Review\n"),
                listOf(SkillFile("SKILL.md", "# Review\n".encodeToByteArray())),
            ),
        )
        repository.saveGroup(Group("mobile", "Mobile", blockIds = listOf("swift-style"), skillIds = listOf("code-review")))
        repository.saveGroup(Group("empty", "Empty"))
        repository.syncAllGroup()
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = ConfigStore(root.resolve("config.json")),
            installer = installer,
        ).also { m -> runBlocking { m.load() } }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a marked group expands into its members and a standalone mark stays untagged`() {
        val items = libraryInstallItems(setOf(swift, mobile), model.catalog, model.blocks, model.skills)

        // swift-style is marked on its own and sits in the group: one write, and untagged, so a later
        // "remove group" does not take away what was installed standalone.
        assertEquals(listOf(swift, review), items.map { it.key })
        assertNull((items.first() as InstallItem.Rule).group)
        assertTrue(items[1] is InstallItem.SkillCopy)
    }

    @Test
    fun `a group member carries the group tag when only the group is marked`() {
        val items = libraryInstallItems(setOf(mobile), model.catalog, model.blocks, model.skills)

        assertEquals(listOf(swift, review), items.map { it.key })
        assertEquals("mobile", (items.first() as InstallItem.Rule).group)
    }

    @Test
    fun `install writes every marked object into every chosen place and clears nothing by itself`() {
        model.toggleMark(swift)
        model.toggleMark(review)

        val report = runBlocking { model.installMarked(setOf("agent:claude-code", "project:/code/atlas")) }

        assertEquals(4, report.written)
        assertEquals(setOf("agent:claude-code", "project:/code/atlas"), installs.single().first)
        assertEquals(listOf(swift, review), installs.single().second.map { it.key })
        assertEquals(setOf(swift, review), model.marked)
    }

    @Test
    fun `installing into no place writes nothing`() {
        model.toggleMark(swift)

        val report = runBlocking { model.installMarked(emptySet()) }

        assertEquals(0, report.written)
        assertTrue(installs.isEmpty())
    }

    @Test
    fun `adding to a group appends only what is missing and keeps the existing order`() {
        model.toggleMark(swift)
        model.toggleMark(kotlin)
        model.toggleMark(review)

        val added = model.addMarkedToGroup("mobile")

        val group = model.groups.single { it.id == "mobile" }
        assertEquals(1, added)
        assertEquals(listOf("swift-style", "kotlin-style"), group.blockIds)
        assertEquals(listOf("code-review"), group.skillIds)
    }

    @Test
    fun `a marked group does not join another group`() {
        model.toggleMark(mobile)

        val added = model.addMarkedToGroup("empty")

        assertEquals(0, added)
        assertTrue(model.groups.single { it.id == "empty" }.blockIds.isEmpty())
    }

    @Test
    fun `a marked subagent joins a group and installs through its native item`() {
        model.toggleMark(subagent)

        assertEquals(1, model.addMarkedToGroup("empty"))
        assertEquals(listOf("reviewer"), model.groups.single { it.id == "empty" }.blockIds)
        val items = libraryInstallItems(setOf(LibraryObjectKey(LibraryObjectKind.GROUP, "empty")), model.catalog, model.blocks, model.skills)
        assertEquals(listOf(subagent), items.map { it.key })
        assertTrue(items.single() is InstallItem.Subagent)
    }

    @Test
    fun `export writes only the marked objects`() {
        model.toggleMark(kotlin)
        val zip = root.resolve("selection.zip")

        model.exportMarked(zip)

        val contents = LibraryArchive(root.resolve("library"), repository).read(zip)
        assertEquals(listOf("kotlin-style"), contents.blocks.map { it.id })
        assertTrue(contents.skills.isEmpty())
        assertTrue(contents.groups.isEmpty())
    }

    @Test
    fun `a deleted object drops out of the selection`() {
        model.toggleMark(kotlin)

        model.deleteBlock("kotlin-style")

        assertTrue(model.marked.isEmpty())
    }
}
