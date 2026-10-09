package dev.ruleblend.core.usecase

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.skillTreeFingerprint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourcesStateStoreTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-sources-state")
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(dir)
    }

    @Test
    fun `a missing sidecar starts empty`() {
        assertEquals(SourcesState(), SourcesStateStore(dir).load())
    }

    @Test
    fun `an unreadable sidecar starts empty`() {
        val store = SourcesStateStore(dir)
        Files.writeString(store.file, "not json")

        assertEquals(SourcesState(), store.load())
    }

    @Test
    fun `state round trips through the sidecar`() {
        val store = SourcesStateStore(dir)
        val state = SourcesState(
            sources = listOf(
                SourceStateSource(
                    repository = "https://example.com/skills.git",
                    lastCheckedAt = "2026-09-05T12:00:00Z",
                    remoteHead = "abc123",
                    upstreamTreeFingerprints = mapOf("review" to "tree-1"),
                ),
            ),
        )

        store.save(state)

        assertEquals(state, store.load())
        assertEquals("sources-state.json", store.file.fileName.toString())
        assertTrue(store.file.exists())
        assertTrue(store.file.readText().endsWith("\n"))
    }

    @Test
    fun `cached upstream fingerprint is replayed against the current library tree`() {
        val repository = LibraryRepository(dir.resolve("library")).also { it.init() }
        val localFiles = listOf(SkillFile("SKILL.md", "Local\n".encodeToByteArray()))
        repository.writeSkill(
            SkillSnapshot(
                Skill(
                    id = "review",
                    name = "Review",
                    content = "Local\n",
                    source = GitSkillSource("https://example.com/skills.git", "old", "skills/review"),
                ),
                localFiles,
            ),
        )
        val upstreamFingerprint = skillTreeFingerprint(
            listOf(SkillFile("SKILL.md", "Upstream\n".encodeToByteArray())),
        )
        val state = SourceCheckReport(
            perRepo = listOf(
                SourceCheckRepository("https://example.com/skills.git", "new", SourceCheckRepositoryStatus.CHECKED),
            ),
            perSkill = listOf(
                SourceCheckSkill(
                    "review",
                    "https://example.com/skills.git",
                    "skills/review",
                    SourceCheckSkillStatus.UPDATE_AVAILABLE,
                    upstreamFingerprint,
                ),
            ),
        ).toSourcesState("2026-09-05T12:00:00Z")

        val cached = state.cachedSourceCheck(repository)

        assertEquals("2026-09-05T12:00:00Z", cached.lastCheckedAt)
        assertEquals(SourceCheckSkillStatus.UPDATE_AVAILABLE, cached.report?.perSkill?.single()?.status)
    }

    @Test
    fun `a path deleted upstream survives in the cached result`() {
        val repository = LibraryRepository(dir.resolve("library")).also { it.init() }
        repository.writeSkill(
            SkillSnapshot(
                Skill(
                    id = "review",
                    name = "Review",
                    content = "Local\n",
                    source = GitSkillSource("https://example.com/skills.git", "old", "skills/review"),
                ),
                listOf(SkillFile("SKILL.md", "Local\n".encodeToByteArray())),
            ),
        )
        val state = SourceCheckReport(
            perRepo = listOf(
                SourceCheckRepository("https://example.com/skills.git", "new", SourceCheckRepositoryStatus.CHECKED),
            ),
            perSkill = listOf(
                SourceCheckSkill(
                    "review",
                    "https://example.com/skills.git",
                    "skills/review",
                    SourceCheckSkillStatus.PATH_MISSING,
                ),
            ),
        ).toSourcesState("2026-09-05T12:00:00Z")

        val cached = state.cachedSourceCheck(repository)

        assertEquals(SourceCheckSkillStatus.PATH_MISSING, cached.report?.perSkill?.single()?.status)
    }
}
