package dev.ruleblend.core.exchange

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryArchiveTest {

    private lateinit var dir: Path
    private lateinit var libraryRoot: Path
    private lateinit var repository: LibraryRepository
    private lateinit var archive: LibraryArchive

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-archive")
        libraryRoot = dir.resolve("library")
        repository = LibraryRepository(libraryRoot)
        repository.init()
        archive = LibraryArchive(libraryRoot, repository)
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(dir)
    }

    private fun zipPath() = dir.resolve("export.zip")

    @Test
    fun `failed export preserves the previous archive and removes temporary files`() {
        val previous = "Previous archive".encodeToByteArray()
        Files.write(zipPath(), previous)
        val unavailableSource = LibraryArchive(dir.resolve("missing-library"), repository)
        assertFailsWith<java.nio.file.NoSuchFileException> { unavailableSource.export(zipPath()) }
        kotlin.test.assertContentEquals(previous, Files.readAllBytes(zipPath()))
        Files.list(dir).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".ruleblend-export-") })
        }
    }

    @Test
    fun embeddedGroupAndProfileIdsMustMatchTheirArchivePaths() {
        for (folder in listOf("groups", "profiles")) {
            for (id in listOf("../../outside", "other", "all")) {
                ZipOutputStream(zipPath().outputStream()).use { out ->
                    out.putNextEntry(ZipEntry("$folder/safe.yaml"))
                    out.write("id: $id\nname: Unsafe\n".encodeToByteArray())
                    out.closeEntry()
                }

                assertFailsWith<IllegalArgumentException>("$folder: $id") { archive.plan(zipPath()) }
                assertTrue(repository.listGroups().isEmpty())
                assertTrue(repository.listProfiles().isEmpty())
                assertTrue(Files.notExists(dir.resolve("outside.yaml")))
            }
        }
    }

    @Test
    fun reservedRedirectIdsRejectTheArchiveBeforeImport() {
        for (id in listOf("ruleblend-import", "kitbash-import")) {
            writeArchive(blocks = mapOf("valid" to blockFile("Valid", 1, "Body"), id to blockFile("Reserved", 1, "Body")))

            assertFailsWith<IllegalArgumentException> { archive.plan(zipPath()) }
            assertTrue(repository.listBlocks().isEmpty())
        }
    }

    @Test
    fun exportThenReadRoundTrips() {
        val block = Block(id = "git-no-commit", name = "Git No Commit", version = 2, content = "Never commit.")
        val group = Group(id = "ios", name = "iOS", blockIds = listOf("git-no-commit"))
        val profile = Profile(id = "review", name = "Review", groupIds = listOf("ios"))
        repository.writeBlock(block)
        repository.saveGroup(group)
        repository.saveProfile(profile)

        archive.export(zipPath())
        val contents = archive.read(zipPath())

        assertEquals(listOf(block), contents.blocks)
        assertEquals(listOf(group), contents.groups)
        assertTrue(contents.skills.isEmpty())
        assertEquals(listOf(profile), contents.profiles)
    }

    @Test
    fun exportOmitsGitDirectory() {
        repository.writeBlock(Block(id = "a", name = "A", content = "x"))

        archive.export(zipPath())

        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(zipPath().toFile().inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                names += entry.name
            }
        }
        assertEquals(listOf("blocks/a.md"), names)
        assertTrue(names.none { it.contains(".git") })
    }

    @Test
    fun exportOfASelectionWritesOnlyTheChosenObjects() {
        repository.writeBlock(Block(id = "a", name = "A", content = "a"))
        repository.writeBlock(Block(id = "b", name = "B", content = "b"))
        repository.writeSkill(
            SkillSnapshot(
                Skill(id = "review", name = "review", description = "d", content = "# Review\n"),
                listOf(SkillFile("SKILL.md", "# Review\n".encodeToByteArray())),
            ),
        )

        archive.export(zipPath(), ArchiveSelection(blockIds = setOf("a"), skillIds = setOf("review")))

        val contents = archive.read(zipPath())
        assertEquals(listOf("a"), contents.blocks.map { it.id })
        assertEquals(listOf("review"), contents.skills.map { it.skill.id })
        assertTrue(contents.groups.isEmpty())
    }

    @Test
    fun exportOfAGroupCarriesItsMembers() {
        repository.writeBlock(Block(id = "a", name = "A", content = "a"))
        repository.writeBlock(Block(id = "b", name = "B", content = "b"))
        repository.writeSkill(
            SkillSnapshot(
                Skill(id = "review", name = "review", description = "d", content = "# Review\n"),
                listOf(SkillFile("SKILL.md", "# Review\n".encodeToByteArray())),
            ),
        )
        repository.saveGroup(Group(id = "ios", name = "iOS", blockIds = listOf("a"), skillIds = listOf("review")))

        archive.export(zipPath(), ArchiveSelection(groupIds = setOf("ios")))

        val contents = archive.read(zipPath())
        assertEquals(listOf("ios"), contents.groups.map { it.id })
        assertEquals(listOf("a"), contents.blocks.map { it.id })
        assertEquals(listOf("review"), contents.skills.map { it.skill.id })
    }

    @Test
    fun exportOfAProfileCarriesItsObjectsAndGroups() {
        repository.writeBlock(Block(id = "rule", name = "Rule", content = "rule"))
        repository.writeBlock(Block(id = "agent", name = "Agent", type = dev.ruleblend.core.model.BlockType.SUBAGENT, content = "agent"))
        repository.writeBlock(Block(id = "group-rule", name = "Group rule", content = "group"))
        repository.writeSkill(
            SkillSnapshot(
                Skill(id = "review", name = "Review", content = "# Review\n"),
                listOf(SkillFile("SKILL.md", "# Review\n".encodeToByteArray())),
            ),
        )
        repository.saveGroup(Group(id = "shared", name = "Shared", blockIds = listOf("group-rule")))
        repository.saveProfile(
            Profile(
                id = "review-mode",
                name = "Review mode",
                blockIds = listOf("rule"),
                skillIds = listOf("review"),
                subagentIds = listOf("agent"),
                groupIds = listOf("shared"),
            ),
        )

        archive.export(zipPath(), ArchiveSelection(profileIds = setOf("review-mode")))

        val contents = archive.read(zipPath())
        assertEquals(listOf("review-mode"), contents.profiles.map { it.id })
        assertEquals(listOf("shared"), contents.groups.map { it.id })
        assertEquals(setOf("rule", "agent", "group-rule"), contents.blocks.mapTo(mutableSetOf()) { it.id })
        assertEquals(listOf("review"), contents.skills.map { it.skill.id })
    }

    @Test
    fun importOfANewBlockWritesItWithTheArchiveVersion() {
        writeArchive(blocks = mapOf("a" to blockFile(name = "A", version = 7, content = "text")))

        val plan = archive.plan(zipPath())
        archive.apply(plan, blockIds = setOf("a"), groupIds = emptySet())

        val imported = repository.loadBlock("a")
        assertEquals(7, imported?.version)
        assertEquals("text", imported?.content)
    }

    @Test
    fun importOfAGroupKeepsTheVersionTheArchiveCarries() {
        repository.saveGroup(Group(id = "ios", name = "iOS", blockIds = listOf("a"), version = 3))
        writeArchive(groups = mapOf("ios" to groupFile(id = "ios", name = "iOS", version = 10, blockIds = listOf("a", "b"))))

        val plan = archive.plan(zipPath())
        assertEquals(ChangeKind.UPDATE, plan.groups.single().kind)
        archive.apply(plan, blockIds = emptySet(), groupIds = setOf("ios"))

        val imported = repository.listGroups().single { it.id == "ios" }
        assertEquals(10, imported.version, "an imported group is the archive's revision, not a local one")
        assertEquals(listOf("a", "b"), imported.blockIds)
    }

    @Test
    fun importOfAProfileKeepsTheVersionTheArchiveCarries() {
        repository.saveProfile(Profile(id = "review", name = "Review", blockIds = listOf("a"), version = 3))
        writeArchive(
            profiles = mapOf(
                "review" to profileFile(id = "review", name = "Review", version = 10, blockIds = listOf("a", "b")),
            ),
        )

        val plan = archive.plan(zipPath())
        assertEquals(ChangeKind.UPDATE, plan.profiles.single().kind)
        archive.apply(
            plan,
            blockIds = emptySet(),
            groupIds = emptySet(),
            profileIds = setOf("review"),
        )

        val imported = requireNotNull(repository.loadProfile("review"))
        assertEquals(10, imported.version)
        assertEquals(listOf("a", "b"), imported.blockIds)
    }

    @Test
    fun applyOnlyWritesPickedItems() {
        writeArchive(
            blocks = mapOf(
                "a" to blockFile(name = "A", version = 1, content = "a"),
                "b" to blockFile(name = "B", version = 1, content = "b"),
            ),
        )

        val plan = archive.plan(zipPath())
        archive.apply(plan, blockIds = setOf("a"), groupIds = emptySet())

        assertEquals("a", repository.loadBlock("a")?.content)
        assertNull(repository.loadBlock("b"))
    }

    @Test
    fun conflictIsNotWrittenUnlessPicked() {
        repository.writeBlock(Block(id = "a", name = "A", version = 5, content = "ours"))
        writeArchive(blocks = mapOf("a" to blockFile(name = "A", version = 1, content = "theirs")))

        val plan = archive.plan(zipPath())
        assertEquals(ChangeKind.CONFLICT, plan.blocks.single().kind)

        archive.apply(plan, blockIds = emptySet(), groupIds = emptySet())
        assertEquals("ours", repository.loadBlock("a")?.content)
    }

    @Test
    fun pickingAConflictTakesTheArchiveVersion() {
        repository.writeBlock(Block(id = "a", name = "A", version = 5, content = "ours"))
        writeArchive(blocks = mapOf("a" to blockFile(name = "A", version = 1, content = "theirs")))

        val plan = archive.plan(zipPath())
        archive.apply(plan, blockIds = setOf("a"), groupIds = emptySet())

        val imported = repository.loadBlock("a")
        assertEquals("theirs", imported?.content)
        assertEquals(1, imported?.version)
    }

    @Test
    fun theAllGroupIsNeverExportedOrImported() {
        repository.writeBlock(Block(id = "a", name = "A", content = "x"))
        repository.saveGroup(Group(id = ALL_GROUP_ID, name = ALL_GROUP_ID, blockIds = listOf("a")))

        archive.export(zipPath())
        ZipFile(zipPath().toFile()).use { assertTrue(it.getEntry("groups/all.yaml") == null) }
        val contents = archive.read(zipPath())

        assertTrue(contents.groups.none { it.id == ALL_GROUP_ID })
    }

    @Test
    fun unexpectedOrEscapingEntriesRejectTheWholeArchive() {
        writeArchive(
            blocks = mapOf("a" to blockFile(name = "A", version = 1, content = "a")),
            extra = mapOf("README.md" to "not a block", "../../evil.md" to "---\nname: Evil\n---\nowned"),
        )

        assertFailsWith<IllegalArgumentException> { archive.plan(zipPath()) }
        assertTrue(repository.listBlocks().isEmpty())
    }

    @Test
    fun strayFilesInTheLibraryFolderStayOutOfTheArchive() {
        repository.writeBlock(Block(id = "a", name = "A", content = "x"))
        Files.writeString(libraryRoot.resolve(".DS_Store"), "finder")
        Files.writeString(libraryRoot.resolve("blocks/.DS_Store"), "finder")
        Files.writeString(libraryRoot.resolve("blocks/a.md~"), "editor backup")
        Files.writeString(libraryRoot.resolve("notes.txt"), "not portable")

        archive.export(zipPath())

        ZipFile(zipPath().toFile()).use { zip ->
            assertEquals(listOf("blocks/a.md"), zip.entries().asSequence().map { it.name }.toList())
        }
        assertEquals(listOf("a"), archive.read(zipPath()).blocks.map { it.id })
    }

    @Test
    fun osFolderMetadataInAnArchiveIsSkipped() {
        writeArchive(
            blocks = mapOf("a" to blockFile(name = "A", version = 1, content = "a")),
            extra = mapOf(
                ".DS_Store" to "finder",
                "blocks/.DS_Store" to "finder",
                "__MACOSX/blocks/._a.md" to "resource fork",
                "groups/Thumbs.db" to "explorer",
            ),
        )

        assertEquals(listOf("a"), archive.read(zipPath()).blocks.map { it.id })
    }

    @Test
    fun randomBytesAreNotAnEmptyArchive() {
        Files.write(zipPath(), byteArrayOf(1, 2, 3, 4))

        assertFailsWith<java.util.zip.ZipException> { archive.plan(zipPath()) }
        assertTrue(repository.listBlocks().isEmpty())
    }

    @Test
    fun duplicateNormalizedPathsRejectTheWholeArchive() {
        val block = blockFile(name = "A", version = 1, content = "a")
        writeArchive(blocks = mapOf("a" to block), extra = mapOf("blocks\\a.md" to block))

        assertFailsWith<IllegalArgumentException> { archive.plan(zipPath()) }
        assertTrue(repository.listBlocks().isEmpty())
    }

    @Test
    fun readRejectsAnEntryThatDecompressesPastTheSizeCap() {
        // A run of repeated bytes compresses to a tiny zip entry but decompresses past the cap.
        writeArchive(blocks = mapOf("bomb" to "x".repeat(15 * 1024 * 1024)))

        assertFailsWith<IllegalArgumentException> { archive.read(zipPath()) }
    }

    @Test
    fun exportThenReadRoundTripsCompleteSkills() {
        val forkedFrom = GitSkillSource("https://example.com/skills", "base123", "skills/review")
        val skill = Skill(
            "review",
            "Review",
            version = "2.4.0",
            content = "# Review\n",
            forkedFrom = forkedFrom,
        )
        val snapshot = SkillSnapshot(
            skill,
            listOf(
                SkillFile("SKILL.md", skill.content.encodeToByteArray()),
                SkillFile("references/checklist.md", "Tests\n".encodeToByteArray(), executable = true),
            ),
        )
        repository.writeSkill(snapshot)

        archive.export(zipPath())
        val contents = archive.read(zipPath())

        assertEquals(snapshot.skill, contents.skills.single().skill)
        assertEquals(snapshot.files.toSet(), contents.skills.single().files.toSet())
        assertEquals(forkedFrom, contents.skills.single().skill.forkedFrom)

        val importedRoot = dir.resolve("imported-library")
        val importedRepository = LibraryRepository(importedRoot).also { it.init() }
        val importedArchive = LibraryArchive(importedRoot, importedRepository)
        val plan = importedArchive.plan(zipPath())
        importedArchive.apply(plan, emptySet(), emptySet(), setOf(skill.id))

        assertEquals(forkedFrom, importedRepository.loadSkill(skill.id)?.forkedFrom)
        assertEquals(snapshot.files.toSet(), importedRepository.loadSkillSnapshot(skill.id)?.files?.toSet())
        importedArchive.export(importedRoot.resolve("reexport.zip"))
        assertEquals(snapshot.files.toSet(), archive.read(importedRoot.resolve("reexport.zip")).skills.single().files.toSet())
    }

    @Test
    fun archiveImportWritesPickedSkillOnly() {
        val meta = "name: Review\ndescription: Safe review\nversion: 2.4.0\n"
        writeArchive(
            extra = mapOf(
                "skills/review/meta.yaml" to meta,
                "skills/review/files/SKILL.md" to "# Review\n",
                "skills/review/files/references/checklist.md" to "Tests\n",
            ),
        )

        val plan = archive.plan(zipPath())
        archive.apply(plan, emptySet(), emptySet(), setOf("review"))

        assertEquals("# Review\n", repository.loadSkill("review")?.content)
        assertEquals("Tests\n", libraryRoot.resolve("skills/review/files/references/checklist.md").toFile().readText())
    }

    @Test
    fun archivedSkillCannotEscapeItsFilesDirectory() {
        writeArchive(
            extra = mapOf(
                "skills/review/meta.yaml" to "name: Review\n",
                "skills/review/files/SKILL.md" to "# Review\n",
                "skills/review/files/../outside.md" to "outside\n",
            ),
        )

        assertFailsWith<IllegalArgumentException> { archive.read(zipPath()) }
    }

    private fun blockFile(name: String, version: Int, content: String): String =
        "---\nname: \"$name\"\ndescription: \"\"\nversion: $version\ntype: \"rule\"\n---\n$content"

    private fun groupFile(id: String, name: String, version: Int, blockIds: List<String>): String =
        buildString {
            appendLine("id: \"$id\"")
            appendLine("name: \"$name\"")
            appendLine("description: \"\"")
            appendLine("blockIds:")
            blockIds.forEach { appendLine("  - \"$it\"") }
            appendLine("skillIds: []")
            appendLine("version: $version")
        }

    private fun profileFile(id: String, name: String, version: Int, blockIds: List<String>): String =
        buildString {
            appendLine("id: \"$id\"")
            appendLine("name: \"$name\"")
            appendLine("description: \"\"")
            appendLine("blockIds:")
            blockIds.forEach { appendLine("  - \"$it\"") }
            appendLine("skillIds: []")
            appendLine("subagentIds: []")
            appendLine("groupIds: []")
            appendLine("version: $version")
        }

    private fun writeArchive(
        blocks: Map<String, String> = emptyMap(),
        groups: Map<String, String> = emptyMap(),
        profiles: Map<String, String> = emptyMap(),
        extra: Map<String, String> = emptyMap(),
    ) {
        ZipOutputStream(zipPath().outputStream().buffered()).use { out ->
            blocks.forEach { (id, text) -> out.entry("blocks/$id.md", text) }
            groups.forEach { (id, text) -> out.entry("groups/$id.yaml", text) }
            profiles.forEach { (id, text) -> out.entry("profiles/$id.yaml", text) }
            extra.forEach { (path, text) -> out.entry(path, text) }
        }
    }

    private fun ZipOutputStream.entry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }
}
