package dev.ruleblend.core.usecase

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.storage.GitSkillImporter
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.commitGitMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode

class CheckSourcesTest {
    private lateinit var root: Path
    private lateinit var upstream: Path
    private lateinit var repositoryUrl: String
    private lateinit var library: LibraryRepository
    private lateinit var checkSources: CheckSources

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-check-sources")
        upstream = root.resolve("upstream")
        Git.init().setDirectory(upstream.toFile()).call().use { git ->
            upstream.resolve("skills/review").createDirectories()
            upstream.resolve("skills/review/SKILL.md").writeText("# Review\n")
            upstream.resolve("skills/review/references.md").writeText("Initial reference\n")
            commit(git, "initial")
        }
        repositoryUrl = upstream.toUri().toString()
        library = LibraryRepository(root.resolve("library")).also { it.init() }
        checkSources = CheckSources(library)
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(root)
    }

    @Test
    fun `does not clone a repository whose HEAD has not changed`() {
        importUpstream()
        val progress = mutableListOf<Pair<Int, Int>>()

        val report = checkSources.check { done, total -> progress += done to total }

        assertEquals(repositoryUrl, report.perRepo.single().repository)
        assertEquals(SourceCheckRepositoryStatus.UNCHANGED, report.perRepo.single().status)
        assertEquals(SourceCheckSkillStatus.CURRENT, report.perSkill.single().status)
        assertNotNull(report.perSkill.single().upstreamTreeFingerprint)
        assertEquals(listOf(0 to 1, 1 to 1), progress)
    }

    @Test
    fun `checks a changed HEAD whose imported path remains identical`() {
        importUpstream()
        upstream.resolve("README.md").writeText("Repository note\n")
        Git.open(upstream.toFile()).use { commit(it, "unrelated") }

        val report = checkSources.check()

        assertEquals(SourceCheckRepositoryStatus.CHECKED, report.perRepo.single().status)
        assertEquals(SourceCheckSkillStatus.CURRENT, report.perSkill.single().status)
    }

    @Test
    fun `reports an update when the imported tree differs upstream`() {
        importUpstream()
        upstream.resolve("skills/review/references.md").writeText("Updated reference\n")
        Git.open(upstream.toFile()).use { commit(it, "update skill") }

        val report = checkSources.check()

        assertEquals(SourceCheckSkillStatus.UPDATE_AVAILABLE, report.perSkill.single().status)
        assertNotNull(report.perSkill.single().upstreamTreeFingerprint)
    }

    @Test
    fun `mode only changes are updates and imported modes stay current after unrelated commits`() {
        importUpstream()
        Git.open(upstream.toFile()).use { git ->
            commitGitMode(git, "skills/review/references.md", FileMode.EXECUTABLE_FILE)
        }
        assertEquals(SourceCheckSkillStatus.UPDATE_AVAILABLE, checkSources.check().perSkill.single().status)

        importUpstream()
        assertEquals(setOf("references.md"), requireNotNull(library.loadSkillSnapshot("review")).files.filter { it.executable }.map { it.path }.toSet())
        upstream.resolve("README.md").writeText("Unrelated change\n")
        Git.open(upstream.toFile()).use { git ->
            // Stage only this file: a Windows checkout must not replace the mode just committed.
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("Unrelated change").setAuthor("Test", "test@example.com").call()
        }
        assertEquals(SourceCheckSkillStatus.CURRENT, checkSources.check().perSkill.single().status)
    }

    @Test
    fun `reports a missing imported path`() {
        importUpstream()
        upstream.resolve("skills/review/references.md").deleteExisting()
        upstream.resolve("skills/review/SKILL.md").deleteExisting()
        upstream.resolve("skills/review").toFile().delete()
        Git.open(upstream.toFile()).use { git ->
            git.rm().addFilepattern("skills/review").call()
            commit(git, "remove skill")
        }

        val report = checkSources.check()

        assertEquals(SourceCheckSkillStatus.PATH_MISSING, report.perSkill.single().status)
    }

    @Test
    fun `a skill at the repository root ignores the checkout's own git directory`() {
        val rootRepository = root.resolve("root-upstream")
        Git.init().setDirectory(rootRepository.toFile()).call().use { git ->
            rootRepository.resolve(".codex-plugin").createDirectories()
            rootRepository.resolve(".codex-plugin/plugin.json").writeText("{\"version\": \"1.0.0\", \"skills\": \".\"}")
            rootRepository.resolve("SKILL.md").writeText("# Root\n")
            commit(git, "initial")
        }
        val rootUrl = rootRepository.toUri().toString()
        val imported = GitSkillImporter().fetch(rootUrl).skills.single()
        assertEquals(listOf(".codex-plugin/plugin.json", "SKILL.md"), imported.files.map { it.path })
        library.writeSkill(imported)

        // A second clone writes a different index and reflog; only a checkout-aware walk stays stable.
        val report = CheckSources(library).check()

        assertEquals(SourceCheckSkillStatus.CURRENT, report.perSkill.single { it.repository == rootUrl }.status)
    }

    @Test
    fun `a source URL that would never be imported is never contacted`() {
        importUpstream()
        val imported = library.listSkills().single()
        library.writeSkill(
            requireNotNull(library.loadSkillSnapshot(imported.id)).let { snapshot ->
                snapshot.copy(
                    skill = snapshot.skill.copy(
                        source = requireNotNull(snapshot.skill.source).copy(repository = "ssh://example.com/skills.git"),
                    ),
                )
            },
        )

        val report = checkSources.check()

        assertEquals(SourceCheckRepositoryStatus.FAILED, report.perRepo.single().status)
        assertEquals(SourceCheckSkillStatus.UNAVAILABLE, report.perSkill.single().status)
        assertEquals("Only HTTPS and local repository URLs are supported", report.perRepo.single().error)
    }

    private fun importUpstream() {
        GitSkillImporter().fetch(repositoryUrl).skills.forEach(library::writeSkill)
    }

    private fun commit(git: Git, message: String) {
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setAuthor("Test", "test@example.com").call()
    }
}
