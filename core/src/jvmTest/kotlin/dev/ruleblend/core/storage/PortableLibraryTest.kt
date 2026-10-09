package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.exchange.BlockChange
import dev.ruleblend.core.exchange.ChangeKind
import dev.ruleblend.core.exchange.ImportPlan
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.exchange.SkillChange
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableLibraryTest {
    private val root = Files.createTempDirectory("ruleblend-portable-library-")
    private val repo = LibraryRepository(root.resolve("library")).apply { init() }
    private val archive = LibraryArchive(root.resolve("library"), repo)

    @AfterTest fun cleanup() { deleteFixtureTree(root) }

    @Test fun rejectsReservedAndTrailingNamesForEveryObject() {
        for (id in listOf("CON", "nul.txt", "COM9", "Lpt1", "tail.", "tail ")) {
            assertFailsWith<IllegalArgumentException>(id) { repo.saveBlock(Block(id, id, content = "text")) }
            assertFailsWith<IllegalArgumentException>(id) { repo.saveGroup(Group(id, id)) }
            assertFailsWith<IllegalArgumentException>(id) { repo.saveProfile(Profile(id, id)) }
            assertFailsWith<IllegalArgumentException>(id) { repo.writeSkill(skill(id)) }
        }
        assertTrue(repo.listBlocks().isEmpty())
        assertTrue(repo.listGroups().isEmpty())
        assertTrue(repo.listSkills().isEmpty())
        assertTrue(repo.listProfiles().isEmpty())
    }

    @Test fun rejectsCaseOnlyReplacementWithoutChangingOriginal() {
        repo.saveBlock(Block("Review", "Review", content = "original"))
        repo.saveGroup(Group("Team", "Team"))
        repo.saveProfile(Profile("Work", "Work"))
        repo.writeSkill(skill("Helper"))
        assertFailsWith<IllegalArgumentException> { repo.saveBlock(Block("review", "Review", content = "changed")) }
        assertFailsWith<IllegalArgumentException> { repo.saveGroup(Group("team", "Team")) }
        assertFailsWith<IllegalArgumentException> { repo.saveProfile(Profile("work", "Work")) }
        assertFailsWith<IllegalArgumentException> { repo.writeSkill(skill("helper")) }
        assertEquals("original", repo.loadBlock("Review")?.content)
    }

    @Test fun checksWholeImportBeforeWritingFirstValidObject() {
        val plan = ImportPlan(
            blocks = listOf(BlockChange(Block("safe", "Safe", content = "ok"), null, ChangeKind.NEW)),
            groups = emptyList(),
            skills = listOf(SkillChange(skill("valid", "scripts/CON.txt"), null, ChangeKind.NEW)),
        )
        assertFailsWith<IllegalArgumentException> { archive.apply(plan, setOf("safe"), emptySet(), setOf("valid")) }
        assertNull(repo.loadBlock("safe"))
        assertTrue(repo.listSkills().isEmpty())
    }

    @Test fun rechecksCaseCollisionCreatedAfterPlanning() {
        val plan = ImportPlan(listOf(
            BlockChange(Block("safe", "Safe", content = "ok"), null, ChangeKind.NEW),
            BlockChange(Block("review", "Review", content = "import"), null, ChangeKind.NEW),
        ), emptyList())
        repo.saveBlock(Block("Review", "Review", content = "local"))
        assertFailsWith<IllegalArgumentException> { archive.apply(plan, setOf("safe", "review"), emptySet()) }
        assertNull(repo.loadBlock("safe"))
        assertEquals("local", repo.loadBlock("Review")?.content)
    }

    @Test fun archivesRejectCaseAliasesAndWindowsNamesOnEveryHost() {
        for (names in listOf(listOf("Review", "review"), listOf("CON"), listOf("tail."))) {
            val zip = root.resolve("input.zip")
            ZipOutputStream(zip.outputStream()).use { output ->
                for (name in names) {
                    output.putNextEntry(ZipEntry("blocks/$name.md"))
                    output.write("---\nname: Rule\nversion: 1\n---\ntext".encodeToByteArray())
                    output.closeEntry()
                }
            }
            assertFailsWith<IllegalArgumentException>(names.toString()) { archive.plan(zip) }
            assertTrue(repo.listBlocks().isEmpty())
        }
    }

    @Test fun skillSnapshotRejectsAmbiguousDirectoriesBeforeStaging() {
        val incoming = skill("helper").let { it.copy(files = it.files + listOf(
            SkillFile("Scripts/a.sh", byteArrayOf(1)), SkillFile("scripts/b.sh", byteArrayOf(2)),
        )) }
        assertFailsWith<IllegalArgumentException> { repo.writeSkill(incoming) }
        assertFalse(Files.exists(root.resolve("library/skills/helper")))
    }

    @Test fun gitTreeIsValidatedBeforeCheckoutCanCollapseCaseAliases() {
        val checkout = root.resolve("checkout")
        Git.init().setDirectory(checkout.toFile()).call().use { git ->
            git.repository.newObjectInserter().use { objects ->
                val blob = objects.insert(Constants.OBJ_BLOB, "text".encodeToByteArray())
                val tree = TreeFormatter().apply {
                    append("README.md", FileMode.REGULAR_FILE, blob)
                    append("readme.md", FileMode.REGULAR_FILE, blob)
                }
                val commit = CommitBuilder().apply {
                    setTreeId(objects.insert(tree))
                    author = PersonIdent("Test", "test@example.com")
                    committer = author
                    message = "Non-portable tree"
                }
                val id = objects.insert(commit)
                objects.flush()
                git.repository.updateRef("HEAD").apply { setNewObjectId(id) }.update()
            }
            assertFailsWith<IllegalArgumentException> { git.checkoutPortableTree() }
            assertFalse(Files.exists(checkout.resolve("README.md")))
        }
    }

    private fun skill(id: String, extra: String? = null): SkillSnapshot = SkillSnapshot(
        Skill(id = id, name = id, content = "---\nname: helper\n---\ntext"),
        listOf(SkillFile("SKILL.md", "---\nname: helper\n---\ntext".encodeToByteArray())) +
            listOfNotNull(extra?.let { SkillFile(it, byteArrayOf(1)) }),
    )
}
