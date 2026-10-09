package dev.ruleblend.app.home

import dev.ruleblend.app.library.LibraryUsageSource
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.SourceCheckMode
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.SourceCheckReport
import dev.ruleblend.core.usecase.SourceCheckRepository
import dev.ruleblend.core.usecase.SourceCheckRepositoryStatus
import dev.ruleblend.core.usecase.SourceCheckSkill
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.core.usecase.toSourcesState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HomeSourceUpdatesTest {
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var stateStore: SourcesStateStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-home-source-updates")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        stateStore = SourcesStateStore(root)
        repository.writeSkill(snapshot("old", "Old body\n"))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `manual check exposes progress and persists its result`() = runBlocking {
        val checkedAt = Instant.parse("2026-09-05T12:00:00Z")
        val report = updateReport("new-tree")
        val model = model(check = { progress ->
            progress(0, 1)
            progress(1, 1)
            report
        }, now = { checkedAt })

        model.refreshSourceUpdates()
        assertTrue(model.sourceUpdates.visible)
        assertEquals(null, model.sourceUpdates.report)

        model.checkSkillSources()

        assertFalse(model.sourceUpdates.checking)
        assertEquals(1, model.sourceUpdates.progressDone)
        assertEquals(1, model.sourceUpdates.progressTotal)
        assertEquals(checkedAt.toString(), model.sourceUpdates.lastCheckedAt)
        assertEquals(setOf("review"), model.sourceUpdates.updateIds)
        assertEquals(REPOSITORY, model.sourceUpdates.sources.single().repository)
        assertEquals(setOf("review"), model.sourceUpdates.sources.single().skillIds)
        assertEquals("new", model.sourceUpdates.sources.single().remoteHead)
        assertEquals("new-tree", stateStore.load().sources.single().upstreamTreeFingerprints["skills/review"])
    }

    @Test
    fun `updating a checked row keeps its id and clears the cached update`() = runBlocking {
        val checkedAt = Instant.parse("2026-09-05T12:00:00Z")
        stateStore.save(updateReport("new-tree").toSourcesState(checkedAt.toString()))
        var updated = emptySet<String>()
        val model = model(update = { ids ->
            updated = ids
            repository.writeSkill(snapshot("new", "New body\n"))
        })

        model.refreshSourceUpdates()
        assertEquals(setOf("review"), model.sourceUpdates.updateIds)

        model.updateSourceSkills(setOf("review"))

        assertEquals(setOf("review"), updated)
        assertEquals(emptySet(), model.sourceUpdates.updateIds)
        assertEquals("review", repository.loadSkill("review")?.id)
        assertEquals("New body\n", repository.loadSkill("review")?.content)
        assertEquals(checkedAt.toString(), model.sourceUpdates.lastCheckedAt)
    }

    @Test
    fun `replaying the sidecar leaves a running check alone`() = runBlocking {
        stateStore.save(updateReport("cached-tree").toSourcesState("2026-09-04T12:00:00Z"))
        var duringCheck: Boolean? = null
        lateinit var model: HomeModel
        model = model(check = {
            runBlocking { model.refreshSourceUpdates() }
            duringCheck = model.sourceUpdates.report != null
            updateReport("fresh-tree")
        })

        model.checkSkillSources()

        assertEquals(false, duringCheck)
        assertEquals("fresh-tree", model.sourceUpdates.report?.perSkill?.single()?.upstreamTreeFingerprint)
    }

    @Test
    fun `on-launch schedule checks through Home and persists the result`() = runBlocking {
        ConfigStore(root.resolve("config.json")).save(AppConfig(sourceCheck = SourceCheckMode.ON_LAUNCH))
        var checks = 0
        val model = model(check = {
            checks++
            updateReport("launch-tree")
        })

        model.checkSkillSourcesOnLaunch()

        assertEquals(1, checks)
        assertEquals(setOf("review"), model.sourceUpdates.updateIds)
        assertEquals("launch-tree", stateStore.load().sources.single().upstreamTreeFingerprints["skills/review"])
    }

    @Test
    fun `manual schedule does not check on launch`() = runBlocking {
        var checks = 0
        val model = model(check = {
            checks++
            updateReport("unused")
        })

        model.checkSkillSourcesOnLaunch()

        assertEquals(0, checks)
        assertEquals(null, model.sourceUpdates.report)
        assertEquals(emptyList(), stateStore.load().sources)
    }

    private fun model(
        check: (((Int, Int) -> Unit) -> SourceCheckReport)? = null,
        update: ((Set<String>) -> Unit)? = null,
        now: () -> Instant = Instant::now,
    ) = HomeModel(
        repository = repository,
        configStore = ConfigStore(root.resolve("config.json")),
        agents = emptyList(),
        usageSource = LibraryUsageSource.None,
        checkImportedSkillSources = check,
        sourceStateStore = stateStore,
        updateImportedSkills = update,
        now = now,
    )

    private fun updateReport(upstreamFingerprint: String) = SourceCheckReport(
        perRepo = listOf(
            SourceCheckRepository(REPOSITORY, "new", SourceCheckRepositoryStatus.CHECKED),
        ),
        perSkill = listOf(
            SourceCheckSkill(
                skillId = "review",
                repository = REPOSITORY,
                path = "skills/review",
                status = SourceCheckSkillStatus.UPDATE_AVAILABLE,
                upstreamTreeFingerprint = upstreamFingerprint,
            ),
        ),
    )

    private fun snapshot(revision: String, content: String) = SkillSnapshot(
        skill = Skill(
            id = "review",
            name = "Review",
            content = content,
            source = GitSkillSource(REPOSITORY, revision, "skills/review"),
        ),
        files = listOf(SkillFile("SKILL.md", content.encodeToByteArray())),
    )

    private companion object {
        const val REPOSITORY = "https://example.com/skills.git"
    }
}
