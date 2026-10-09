package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.GitSkillFetcher
import dev.ruleblend.core.storage.GitSkillImporter
import dev.ruleblend.core.storage.GitSkillImportPlan
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.eclipse.jgit.api.Git

class ImportLibraryTest {

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var fetched: MutableList<String>

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("ruleblend-import-library")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        fetched = mutableListOf()
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

    @Test
    fun `batch update fetches each repository once and preserves local ids`() {
        val firstUpstream = createUpstream("first", mapOf("a" to "old-a", "b" to "old-b"))
        val secondUpstream = createUpstream("second", mapOf("c" to "old-c"))
        val firstRepository = firstUpstream.toUri().toString()
        val secondRepository = secondUpstream.toUri().toString()
        importRepository(firstRepository, mapOf("skills/a" to "local-a", "skills/b" to "local-b"))
        importRepository(secondRepository, mapOf("skills/c" to "local-c"))
        updateUpstream(firstUpstream, mapOf("a" to "new-a", "b" to "new-b"))
        updateUpstream(secondUpstream, mapOf("c" to "new-c"))
        val importer = GitSkillImporter()

        val updated = imports { url -> fetched += url; importer.fetch(url) }
            .updateSkills(setOf("local-a", "local-b", "local-c"))

        assertEquals(mapOf(firstRepository to 1, secondRepository to 1), fetched.groupingBy { it }.eachCount())
        assertEquals(setOf("local-a", "local-b", "local-c"), updated.value.mapTo(mutableSetOf()) { it.id })
        assertEquals("new-a", repository.loadSkill("local-a")?.content)
        assertEquals("new-b", repository.loadSkill("local-b")?.content)
        assertEquals("new-c", repository.loadSkill("local-c")?.content)
    }

    @Test
    fun `batch update validates every upstream path before writing`() {
        val firstRepository = "https://example.com/first.git"
        val secondRepository = "https://example.com/second.git"
        import("local-a", firstRepository, "skills/a", "old-a")
        import("local-b", secondRepository, "skills/b", "old-b")
        val plans = mapOf(
            firstRepository to plan(
                firstRepository,
                snapshot("upstream-a", firstRepository, "skills/a", "new-a"),
            ),
            secondRepository to plan(
                secondRepository,
                snapshot("other", secondRepository, "skills/other", "other"),
            ),
        )

        assertFailsWith<IllegalStateException> {
            imports { url -> plans.getValue(url) }.updateSkills(setOf("local-a", "local-b"))
        }

        assertEquals("old-a", repository.loadSkill("local-a")?.content)
        assertEquals("old-b", repository.loadSkill("local-b")?.content)
    }

    @Test
    fun `batch update rejects unknown and local ids before fetching`() {
        repository.createSkill(Skill("local", "local", content = "local"))

        assertFailsWith<IllegalArgumentException> {
            imports { url -> fetched += url; error("Unexpected fetch") }.updateSkills(setOf("missing"))
        }
        assertFailsWith<IllegalArgumentException> {
            imports { url -> fetched += url; error("Unexpected fetch") }.updateSkills(setOf("local"))
        }

        assertEquals(emptyList(), fetched)
    }

    @Test
    fun `batch import validates every portable tree before writing`() {
        val url = "https://example.com/skills.git"
        val valid = snapshot("a", url, "skills/a", "new-a")
        val invalid = snapshot("b", url, "skills/b", "new-b").let {
            it.copy(files = it.files + SkillFile("assets/NUL.txt", byteArrayOf(1)))
        }
        val incoming = plan(url, valid, invalid)
        assertFailsWith<IllegalArgumentException> {
            imports { incoming }.applyGitSkills(incoming, setOf("skills/a", "skills/b"))
        }
        assertEquals(emptyList(), repository.listSkills())
    }

    private fun imports(fetch: (String) -> GitSkillImportPlan): ImportLibrary {
        val libraryRoot = root.resolve("library")
        return ImportLibrary(
            repository = repository,
            archive = LibraryArchive(libraryRoot, repository),
            configStore = ConfigStore(root.resolve("config.json")),
            gitSkills = GitSkillFetcher(fetch),
        )
    }

    private fun import(id: String, repositoryUrl: String, path: String, content: String) {
        repository.writeSkill(snapshot(id, repositoryUrl, path, content, revision = "old"))
    }

    private fun createUpstream(name: String, skills: Map<String, String>): Path {
        val upstream = root.resolve(name)
        Git.init().setDirectory(upstream.toFile()).call().use { git ->
            skills.forEach { (id, content) ->
                upstream.resolve("skills/$id").createDirectories()
                upstream.resolve("skills/$id/SKILL.md").writeText(content)
            }
            commit(git, "initial")
        }
        return upstream
    }

    private fun importRepository(repositoryUrl: String, idsByPath: Map<String, String>) {
        GitSkillImporter().fetch(repositoryUrl).skills.forEach { snapshot ->
            val source = requireNotNull(snapshot.skill.source)
            repository.writeSkill(snapshot.copy(skill = snapshot.skill.copy(id = idsByPath.getValue(source.path))))
        }
    }

    private fun updateUpstream(upstream: Path, skills: Map<String, String>) {
        Git.open(upstream.toFile()).use { git ->
            skills.forEach { (id, content) -> upstream.resolve("skills/$id/SKILL.md").writeText(content) }
            commit(git, "update skills")
        }
    }

    private fun commit(git: Git, message: String) {
        git.add().addFilepattern(".").call()
        git.commit().setMessage(message).setAuthor("Test", "test@example.com").call()
    }

    private fun plan(repositoryUrl: String, vararg skills: SkillSnapshot): GitSkillImportPlan =
        GitSkillImportPlan(repositoryUrl, revision = "new", version = "2.0", skills = skills.toList())

    private fun snapshot(
        id: String,
        repositoryUrl: String,
        path: String,
        content: String,
        revision: String = "new",
    ): SkillSnapshot {
        val skill = Skill(
            id = id,
            name = id,
            version = if (revision == "old") "1.0" else "2.0",
            content = content,
            source = GitSkillSource(repositoryUrl, revision, path),
        )
        return SkillSnapshot(skill, listOf(SkillFile("SKILL.md", content.encodeToByteArray())))
    }
}
