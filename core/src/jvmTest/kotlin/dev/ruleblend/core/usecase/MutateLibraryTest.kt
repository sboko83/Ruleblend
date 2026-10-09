package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.projectKeyOf
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The steps that used to trail every mutation in the UI model are now part of the mutation. These
 * tests are about exactly that: a caller gets the library back consistent without having to know
 * which of the steps applied to the write it just made.
 */
class MutateLibraryTest {

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private lateinit var mutate: MutateLibrary

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("ruleblend-mutate")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        configStore = ConfigStore(root.resolve("config.json"))
        mutate = MutateLibrary(repository, configStore)
    }

    @AfterTest
    fun tearDown() {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.getFileAttributeView(path, DosFileAttributeView::class.java)?.setReadOnly(false)
                Files.deleteIfExists(path)
            }
        }
    }

    /** A profile holds the same member lists a group does, so a deletion has to leave both. */
    @Test
    fun deletingAnObjectTakesItOutOfEveryProfileThatNamedIt() {
        val rule = mutate.createBlock("Style", BlockType.RULE).value
        val subagent = mutate.createBlock("Reviewer", BlockType.SUBAGENT).value
        val group = mutate.createGroup("Bundle").value
        mutate.saveProfile(
            Profile(
                id = "review",
                name = "review",
                blockIds = listOf(rule.id),
                subagentIds = listOf(subagent.id),
                groupIds = listOf(group.id),
            ),
        )

        mutate.deleteBlock(rule.id)
        mutate.deleteBlock(subagent.id)
        val library = mutate.deleteGroup(group.id).library

        val profile = library.profiles.single { it.id == "review" }
        assertEquals(emptyList(), profile.blockIds)
        assertEquals(emptyList(), profile.subagentIds)
        assertEquals(emptyList(), profile.groupIds)
    }

    /** A binding only the deleted profile explains would leave a chip no screen can reach. */
    @Test
    fun deletingAProfileDetachesItFromEveryProject() {
        val profile = mutate.createProfile("Review").value
        configStore.update {
            it.copy(
                projectProfiles = mapOf(
                    "/one" to listOf(ProfileBinding(profile.id, active = true)),
                    "/two" to listOf(ProfileBinding(profile.id, active = false), ProfileBinding("other", active = true)),
                ),
            )
        }

        mutate.deleteProfile(profile.id)

        val bindings = configStore.load().projectProfiles
        assertNull(bindings[projectKeyOf("/one")])
        assertEquals(listOf("other"), bindings.getValue(projectKeyOf("/two")).map { it.id })
    }

    @Test
    fun createdBlockIsNamedByTheConfiguredFormatAndComesBackInTheSnapshot() {
        configStore.update { it.copy(nameFormat = NameFormat.KEBAB) }

        val created = mutate.createBlock("Git Commit Style", BlockType.RULE)

        assertEquals("git-commit-style", created.value.name)
        assertEquals("git-commit-style", created.value.id)
        assertEquals(listOf("git-commit-style"), created.library.blocks.map { it.id })
    }

    /** A rule lifted from a comparison keeps its body, its scope, and does not take a sibling's id. */
    @Test
    fun ruleCreatedFromTextKeepsTheBodyAndScopeAtVersionOne() {
        val project = root.resolve("atlas").projectKey()
        mutate.createBlock("Style", BlockType.RULE)

        val created = mutate.createRule("Style", "- Prefer val.\n", scope = project)

        assertTrue(created.value.id != "style", created.value.id)
        assertEquals(BlockType.RULE, created.value.type)
        assertEquals("- Prefer val.\n", created.value.content)
        assertEquals(1, created.value.version)
        assertEquals(project, created.library.ruleScopes[created.value.id])
    }

    /** An id that is taken gets a suffix rather than overwriting the block that holds it. */
    @Test
    fun aSecondBlockOfTheSameNameGetsAFreeId() {
        mutate.createBlock("Style", BlockType.RULE)

        val second = mutate.createBlock("Style", BlockType.RULE)

        assertEquals(2, second.library.blocks.size)
        assertTrue(second.value.id != "style", second.value.id)
    }

    @Test
    fun concurrentCreationsNeverOverwriteAnotherNewBlock() {
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..8).map {
                workers.submit<String> {
                    start.await()
                    mutate.createBlock("Style", BlockType.RULE).value.id
                }
            }
            start.countDown()
            val ids = tasks.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(8, ids.toSet().size)
            assertEquals(ids.toSet(), repository.listBlocks().map { it.id }.toSet())
            assertEquals(ids.toSet(), repository.loadGroup(ALL_GROUP_ID)?.blockIds?.toSet())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun generatedBlockIdsAvoidImportRegionNames() {
        for (name in listOf("ruleblend-import", "kitbash-import")) {
            val block = mutate.createBlock(name, BlockType.RULE).value
            assertEquals("$name-2", block.id)
        }
    }

    @Test
    fun duplicatingARuleKeepsItsHeadingAndScope() {
        val scope = root.resolve("atlas").projectKey()
        val original = mutate.createBlock("Style", BlockType.RULE, scope).value
        val source = mutate.saveBlock(original.copy(content = "Body", heading = "Rules", headingLevel = 3), scope).value

        val duplicated = mutate.duplicateBlock(source)

        assertEquals("Rules", duplicated.value.heading)
        assertEquals(3, duplicated.value.headingLevel)
        assertEquals(source.content, duplicated.value.content)
        assertEquals(1, duplicated.value.version)
        assertEquals(scope, duplicated.library.ruleScopes[duplicated.value.id])
        assertEquals(duplicated.value, repository.loadBlock(duplicated.value.id))
    }

    @Test
    fun duplicatingASubagentKeepsItsAssistantFields() {
        val original = mutate.createBlock("Reviewer", BlockType.SUBAGENT).value
        val source = mutate.saveBlock(original.copy(
            content = "Review code",
            variants = mapOf("codex" to SubagentVariant(mapOf("model" to "review-model"))),
        )).value

        val duplicate = mutate.duplicateBlock(source).value

        assertEquals(source.variants, duplicate.variants)
        assertEquals(source.content, duplicate.content)
        assertEquals(duplicate, repository.loadBlock(duplicate.id))
    }

    @Test
    fun savingAGroupReturnsThePersistedMembershipVersion() {
        val group = mutate.createGroup("Review").value

        val saved = mutate.saveGroup(group.copy(blockIds = listOf("style")))

        assertEquals(2, saved.value.version)
        assertEquals(saved.value, saved.library.groups.single { it.id == group.id })
        assertEquals(saved.value, repository.loadGroup(group.id))
    }

    /** The generated "all" group must never lag behind a mutation; it is synced by the mutation. */
    @Test
    fun everyMutationLeavesTheAllGroupInStep() {
        val created = mutate.createBlock("Style", BlockType.RULE)
        assertEquals(listOf(created.value.id), created.library.groups.single { it.id == ALL_GROUP_ID }.blockIds)

        val deleted = mutate.deleteBlock(created.value.id)

        assertEquals(emptyList(), deleted.library.groups.single { it.id == ALL_GROUP_ID }.blockIds)
    }

    @Test
    fun aRuleCreatedInAProjectScopeComesBackScopedToIt() {
        val project = root.resolve("atlas").projectKey()

        val created = mutate.createBlock("Style", BlockType.RULE, scope = project)

        assertEquals(project, created.library.ruleScopes[created.value.id])
    }

    /** An MCP block has no scope, so creating one must not write a rule scope for it. */
    @Test
    fun anMcpBlockIsNeverScoped() {
        val created = mutate.createBlock("Server", BlockType.MCP, scope = root.resolve("atlas").toString())

        assertNull(created.library.ruleScopes[created.value.id])
    }

    @Test
    fun deletingABlockClearsItsScopeAndItsGroupMemberships() {
        val block = mutate.createBlock("Style", BlockType.RULE, scope = root.resolve("atlas").toString()).value
        val group = mutate.createGroup("Mobile").value
        mutate.saveGroup(group.copy(blockIds = listOf(block.id)))

        val deleted = mutate.deleteBlock(block.id)

        assertNull(deleted.library.ruleScopes[block.id])
        assertEquals(emptyList(), deleted.library.groups.single { it.id == group.id }.blockIds)
        assertFalse(deleted.library.blocks.any { it.id == block.id })
    }

    @Test
    fun splittingKeepsTheSourceAndGivesEveryPartItsGroupsAndScope() {
        val scope = root.resolve("atlas").projectKey()
        val source = mutate.createBlock("Style", BlockType.RULE, scope = scope).value
        val group = mutate.createGroup("Mobile").value
        mutate.saveGroup(group.copy(blockIds = listOf(source.id)))

        val split = mutate.splitBlock(
            source,
            listOf(LibraryPart("Naming", "Name things well."), LibraryPart("Layout", "Lay things out.")),
            description = "Split from Style",
        )

        assertEquals(2, split.value.size)
        assertTrue(split.library.blocks.any { it.id == source.id }, "the source block is left untouched")
        split.value.forEach { part ->
            assertEquals(scope, split.library.ruleScopes[part.id])
            assertTrue(part.id in split.library.groups.single { it.id == group.id }.blockIds)
        }
    }

    @Test
    fun theAllGroupIsGeneratedAndSoIsNeverDeleted() {
        mutate.createBlock("Style", BlockType.RULE)

        val after = mutate.deleteGroup(ALL_GROUP_ID)

        assertTrue(after.library.groups.any { it.id == ALL_GROUP_ID })
    }

    @Test
    fun profilesUseTheSameMutationSnapshotAndVersionMembershipContract() {
        val created = mutate.createProfile("Testing Mode")
        val saved = mutate.saveProfile(created.value.copy(blockIds = listOf("style")))
        val renamed = mutate.saveProfile(saved.value.copy(name = "Testing"))

        assertEquals("testing-mode", created.value.id)
        assertEquals(2, saved.value.version)
        assertEquals(2, renamed.value.version)
        assertEquals(listOf("testing-mode"), renamed.library.profiles.map { it.id })
    }

    @Test
    fun deletingASkillTakesItOutOfEveryGroup() {
        val skill = mutate.createSkill("Docs", "Writes docs").value
        val group = mutate.createGroup("Mobile").value
        mutate.saveGroup(group.copy(skillIds = listOf(skill.id)))

        val deleted = mutate.deleteSkill(skill.id)

        assertEquals(emptyList(), deleted.library.groups.single { it.id == group.id }.skillIds)
        assertFalse(deleted.library.skills.any { it.id == skill.id })
    }

    @Test
    fun creatingASkillAppliesItsStructuredMetadataToProvidedInstructions() {
        val created = mutate.createSkill(
            name = "docs",
            description = "Writes docs",
            content = "# Documentation\n\nWrite the decision.\n",
        ).value

        assertEquals(
            "---\nname: \"docs\"\ndescription: \"Writes docs\"\n---\n\n# Documentation\n\nWrite the decision.\n",
            created.content,
        )
    }
}
