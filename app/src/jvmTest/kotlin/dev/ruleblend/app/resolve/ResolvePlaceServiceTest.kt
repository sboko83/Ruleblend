package dev.ruleblend.app.resolve

import dev.ruleblend.app.place.projectPlaceId
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Applying strategies to real files. The batch is the one place in the app that can destroy text a
 * person typed, so every assertion here is about a file on disk after the write: what the run holds,
 * what the library holds, and what was left exactly as it was.
 */
class ResolvePlaceServiceTest {

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore

    private val kotlinStyle = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")
    private val gitFlow = Block(id = "git-flow", name = "Git flow", content = "Rebase, do not merge.")

    @Test fun `restore puts the library body back and leaves the hand-written text alone`() {
        val project = project("ledger-kmp")
        project.resolve("AGENTS.md").writeText("# House rules\n\nKeep it short.\n")
        install(project, kotlinStyle)
        edit(project, kotlinStyle, "Edited by hand.")

        val report = service().apply(plan(project, step(kotlinStyle, ResolveStrategy.RESTORE)))

        assertEquals(ResolveReport(restored = 1), report)
        val text = project.resolve("AGENTS.md").readText()
        assertTrue(kotlinStyle.content in text, "the library body is back")
        assertTrue("Edited by hand." !in text, "the edit is gone, which is what restore means")
        assertTrue("Keep it short." in text, "text outside the managed region is never touched")
        assertEquals(kotlinStyle.content, repository.loadBlock(kotlinStyle.id)!!.content)
    }

    @Test fun `save as new version commits the edit and resyncs the file to it`() {
        val project = project("nimbus-api")
        install(project, gitFlow)
        edit(project, gitFlow, "Rebase, always.")

        val report = service().apply(plan(project, step(gitFlow, ResolveStrategy.SAVE_AS_VERSION)))

        assertEquals(ResolveReport(saved = 1), report)
        val saved = repository.loadBlock(gitFlow.id)!!
        assertEquals("Rebase, always.", saved.content)
        assertEquals(gitFlow.version + 1, saved.version)
        assertTrue("Rebase, always." in project.resolve("AGENTS.md").readText())
    }

    @Test fun `two answers in one place are both honoured by the single write it takes`() {
        val project = project("mesh-sync")
        // Two edited blocks of one run are ambiguous by design, so two answers in one place mean two
        // runs — the two agent files this project keeps, each with a rule of its own.
        install(project, kotlinStyle, files = listOf("AGENTS.md"))
        install(project, gitFlow, files = listOf("CODEX.md"))
        edit(project, kotlinStyle, "Edited by hand.", file = "AGENTS.md")
        edit(project, gitFlow, "Rebase, always.", file = "CODEX.md")

        val report = service().apply(
            plan(
                project,
                step(kotlinStyle, ResolveStrategy.RESTORE),
                step(gitFlow, ResolveStrategy.SAVE_AS_VERSION),
            ),
        )

        assertEquals(ResolveReport(restored = 1, saved = 1), report)
        assertEquals("Rebase, always.", repository.loadBlock(gitFlow.id)!!.content)
        assertTrue(
            kotlinStyle.content in project.resolve("AGENTS.md").readText(),
            "the restored block took the library body",
        )
        assertTrue(
            "Rebase, always." in project.resolve("CODEX.md").readText(),
            "the saved edit survived the rebuild of its run",
        )
    }

    @Test fun `a place that refuses its write costs the batch that place and nothing else`() {
        val good = project("quartz-kmp")
        install(good, kotlinStyle)
        edit(good, kotlinStyle, "Edited by hand.")
        // A stale snapshot can still offer a place whose run turned ambiguous meanwhile: two edited
        // blocks of one run, which core refuses to attribute — and refusing is the point.
        val bad = project("dune-sandbox")
        install(bad, kotlinStyle)
        install(bad, gitFlow)
        edit(bad, kotlinStyle, "One edit.")
        edit(bad, gitFlow, "Another edit.")

        val report = service().apply(
            ResolvePlan(
                places = listOf(
                    ResolvePlaceStep(projectPlaceId(bad.toString()), "dune-sandbox", listOf(step(kotlinStyle, ResolveStrategy.SAVE_AS_VERSION))),
                    ResolvePlaceStep(projectPlaceId(good.toString()), "quartz-kmp", listOf(step(kotlinStyle, ResolveStrategy.RESTORE))),
                ),
                skipped = 2,
            ),
        )

        assertEquals(listOf("dune-sandbox"), report.failed)
        assertEquals(1, report.restored)
        assertEquals(2, report.skipped, "what the batch was told to leave alone is still part of its answer")
        assertTrue(kotlinStyle.content in good.resolve("AGENTS.md").readText(), "the other place was written")
        assertTrue("One edit." in bad.resolve("AGENTS.md").readText(), "the refused place is untouched")
        assertEquals(kotlinStyle.content, repository.loadBlock(kotlinStyle.id)!!.content, "no version was committed")
    }

    @Test fun `a place the config no longer knows is reported, not written`() {
        val report = service().apply(
            ResolvePlan(listOf(ResolvePlaceStep("project:/gone", "gone", listOf(step(kotlinStyle, ResolveStrategy.RESTORE))))),
        )

        assertEquals(ResolveReport(failed = listOf("gone")), report)
    }

    @Test fun `restore refuses a neighbouring edit that appeared after the scan`() {
        val project = project("stale-restore")
        install(project, kotlinStyle)
        install(project, gitFlow)
        edit(project, kotlinStyle, "Selected edit.")
        edit(project, gitFlow, "Unselected edit.")
        val before = agents.map { it.projectFile(project).readText() }

        val report = service().apply(plan(project, step(kotlinStyle, ResolveStrategy.RESTORE)))

        assertEquals(listOf("stale-restore"), report.failed)
        assertEquals(0, report.restored)
        assertEquals(before, agents.map { it.projectFile(project).readText() })
    }

    @Test fun `restore leaves a skipped rule in another agent file untouched`() {
        val project = project("skip-other-file")
        install(project, kotlinStyle, files = listOf("AGENTS.md"))
        install(project, gitFlow, files = listOf("CODEX.md"))
        edit(project, kotlinStyle, "Selected edit.", file = "AGENTS.md")
        edit(project, gitFlow, "Skipped edit.", file = "CODEX.md")
        val skipped = project.resolve("CODEX.md").readText()

        val report = service().apply(plan(project, step(kotlinStyle, ResolveStrategy.RESTORE)))

        assertEquals(ResolveReport(restored = 1), report)
        assertEquals(skipped, project.resolve("CODEX.md").readText())
        assertTrue(kotlinStyle.content in project.resolve("AGENTS.md").readText())
    }

    @Test fun `a later ambiguous file rolls back the copy restored earlier`() {
        val project = project("restore-rollback")
        install(project, kotlinStyle)
        install(project, gitFlow)
        edit(project, kotlinStyle, "Selected edit.")
        edit(project, gitFlow, "New neighbour edit.", file = "CODEX.md")
        val before = agents.map { it.projectFile(project).readText() }

        val report = service().apply(plan(project, step(kotlinStyle, ResolveStrategy.RESTORE)))

        assertEquals(listOf("restore-rollback"), report.failed)
        assertEquals(before, agents.map { it.projectFile(project).readText() })
    }

    @Test fun `a removed copy is reported without reinstalling it`() {
        val project = project("removed-copy")

        val report = service().apply(plan(project, step(kotlinStyle, ResolveStrategy.RESTORE)))

        assertEquals(listOf("removed-copy"), report.failed)
        assertTrue(agents.none { Files.exists(it.projectFile(project)) })
    }

    @Test fun `saving an edit leaves a skipped rule in another agent file untouched`() {
        val project = project("save-skip-other-file")
        install(project, kotlinStyle, files = listOf("AGENTS.md"))
        install(project, gitFlow, files = listOf("CODEX.md"))
        edit(project, kotlinStyle, "Selected edit.", file = "AGENTS.md")
        edit(project, gitFlow, "Skipped edit.", file = "CODEX.md")
        val skipped = project.resolve("CODEX.md").readText()

        val report = service().apply(plan(project, step(kotlinStyle, ResolveStrategy.SAVE_AS_VERSION)))

        assertEquals(ResolveReport(saved = 1), report)
        assertEquals(skipped, project.resolve("CODEX.md").readText())
        assertEquals("Selected edit.", repository.loadBlock(kotlinStyle.id)?.content)
    }

    // ---- harness ----

    private val agents = listOf(
        agent("claude-code", "AGENTS.md"),
        agent("codex", "CODEX.md"),
    )

    private fun agent(id: String, file: String) = object : AgentAdapter {
        override val id = id
        override val name = id
        override fun isAvailable() = true
        override fun globalFile(): Path = root.resolve("home-$id/AGENTS.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve(file)
    }

    private fun service() = ResolvePlaceService(configStore, agents, integration(), repository)

    private fun integration() = IntegrationService(blockResolver = repository::loadBlock)

    private fun project(name: String): Path {
        val dir = root.resolve(name).also { it.createDirectories() }
        configStore.update { it.copy(projects = it.projects + dir.toString()) }
        return dir
    }

    private fun install(project: Path, block: Block, files: List<String>? = null) {
        val paths = files?.map(project::resolve) ?: agents.map { it.projectFile(project) }
        paths.forEach { integration().install(it, block) }
    }

    private fun edit(project: Path, block: Block, text: String, file: String? = null) {
        val files = if (file == null) agents.map { it.projectFile(project) } else listOf(project.resolve(file))
        files.forEach { path ->
            val library = repository.loadBlock(block.id)!!.content
            path.writeText(path.readText().replace(library, text))
        }
    }

    private fun step(block: Block, strategy: ResolveStrategy) = ResolveStep(block.id, block.name, strategy)

    private fun plan(project: Path, vararg steps: ResolveStep) = ResolvePlan(
        listOf(ResolvePlaceStep(projectPlaceId(project.toString()), project.fileName.toString(), steps.toList())),
    )

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-resolve-apply")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(kotlinStyle)
        repository.saveBlock(gitFlow)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig())
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }
}
