package dev.ruleblend.app.coverage

import dev.ruleblend.app.fixturePath
import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryInstallReport
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceInstaller
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceRef
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.key
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.SkipReason
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What a cell click does to the fleet. Coverage owns no write of its own: every click here must come
 * back out of the shared installer, and what it wrote must be re-read rather than guessed.
 */
class CoverageModelTest {

    @Test fun `assistant expansion threshold is independent of row defaults and survives rescans`() = runTest {
        repository.writeGroup(Group("style", "Style", blockIds = listOf("style")))
        for (groupsOpen in listOf(false, true)) {
            configStore.save(AppConfig(groupsExpandedByDefault = groupsOpen))
            for (count in listOf(0, 1, 3, 4, 5)) {
                val model = model(count)
                model.rescan()
                assertEquals(if (groupsOpen) setOf("group:style") else emptySet(), model.expansion.rows)
                val opens = count in 2..4
                assertEquals(if (opens) setOf("agents") else emptySet(), model.expansion.columns)
                if (count > 1) {
                    val before = scans
                    model.toggleColumn("agents")
                    assertEquals(before, scans)
                    assertEquals(!opens, model.snapshot.columns.any { it.parentId == "agents" })
                    val expansion = model.expansion
                    model.rescan()
                    assertEquals(expansion, model.expansion)
                }
            }
        }
    }

    @Test fun `fold all operations use filtered aggregates without reading or writing`() = runTest {
        repository.writeGroup(Group("style", "Style", blockIds = listOf("style")))
        val model = model(3)
        model.rescan()
        val before = scans
        model.collapseAllColumns()
        val closedColumns = model.snapshot.columns
        model.expandAllRows()
        assertEquals(setOf("type:RULE", "group:style"), model.expansion.rows)
        model.expandAllRows()
        assertEquals(setOf("type:RULE", "group:style"), model.expansion.rows)
        model.expandAllColumns()
        assertEquals(setOf("agents", "ungrouped"), model.expansion.columns)
        assertTrue(model.snapshot.columns.all { it.parentId != null })
        model.collapseAllRows()
        assertTrue(model.expansion.rows.isEmpty())
        assertTrue(model.snapshot.rows.none { it.kind == CoverageRowKind.OBJECT })
        model.collapseAllColumns()
        assertEquals(closedColumns, model.snapshot.columns)
        model.applyFilters(CoverageFilters(needsAttention = true))
        model.expandAllRows()
        assertTrue(model.expansion.rows.isEmpty())
        assertTrue(model.snapshot.rows.isEmpty())
        model.applyFilters(CoverageFilters())
        assertTrue(model.snapshot.rows.isNotEmpty())
        assertEquals(before, scans)
        assertTrue(calls.isEmpty())
    }

    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore

    private val style = Block("style", "style", content = "Use spaces.")
    private val flow = Block("flow", "flow", content = "Small commits.")
    private val key = LibraryObjectKey(LibraryObjectKind.RULE, "style")

    private val calls = mutableListOf<Triple<String, Set<String>, List<String>>>()
    private var report = LibraryInstallReport(written = 1)
    private var scans = 0

    /** Places that answer every call with a refusal, so a batch can be made to fail in part. */
    private val refusing = mutableSetOf<String>()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-coverage-model")
        repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.writeBlock(style)
        repository.writeBlock(flow)
        configStore = ConfigStore(root.resolve("config.json"))
        // These tests exercise individual expansion actions, independently from the application
        // default that now opens grouped views for a user entering the screen.
        configStore.save(AppConfig(groupsExpandedByDefault = false))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a cell click installs through the shared installer and reads the fleet again`() = runTest {
        val model = model()
        model.rescan()
        val before = scans

        model.setInstalled(key, "project:${fixturePath("/tmp/atlas")}", install = true)

        assertEquals(listOf(Triple("install", setOf("project:${fixturePath("/tmp/atlas")}"), listOf("style"))), calls)
        assertNull(model.notice)
        // A write invalidates the whole scan, so the fleet is read again instead of patched.
        assertEquals(before + 1, scans)
    }

    @Test
    fun `taking an object out is the same call in the other direction`() = runTest {
        val model = model()
        model.rescan()

        model.setInstalled(key, "project:${fixturePath("/tmp/atlas")}", install = false)

        assertEquals(listOf(Triple("remove", setOf("project:${fixturePath("/tmp/atlas")}"), listOf("style"))), calls)
    }

    @Test
    fun `a refused write says so and leaves the scan alone`() = runTest {
        report = LibraryInstallReport(skipped = mapOf(SkipReason.MODIFIED to 1))
        val model = model()
        model.rescan()
        val before = scans

        model.setInstalled(key, "project:${fixturePath("/tmp/atlas")}", install = true)

        assertEquals(CoverageNotice.MODIFIED, model.notice)
        assertEquals(before, scans)
        model.dismissNotice()
        assertNull(model.notice)
    }

    @Test
    fun `opening a row re-cuts the scan without reading the fleet again`() = runTest {
        val model = model()
        model.rescan()
        val before = scans

        model.toggleRow("type:RULE")

        assertEquals(before, scans)
        assertTrue(model.snapshot.rows.any { it.kind == CoverageRowKind.OBJECT && it.key == key })
        model.toggleRow("type:RULE")
        assertTrue(model.snapshot.rows.none { it.kind == CoverageRowKind.OBJECT })
    }

    @Test fun `a marked row plans without touching disk`() = runTest {
        val model = model()
        model.rescan()
        val before = scans

        model.toggleMark("type:RULE")
        val plan = model.bulkPlan(CoverageBulkAction.INSTALL, "ungrouped")

        assertEquals(before, scans)
        assertTrue(calls.isEmpty())
        // atlas already holds "style", so it is planned only "flow"; beacon holds neither.
        assertEquals(listOf(1, 2), plan.steps.map { it.keys.size })
    }

    @Test fun `a batch runs place by place and one refusal does not stop the rest`() = runTest {
        refusing += "project:${fixturePath("/tmp/beacon")}"
        val model = model()
        model.rescan()
        val before = scans
        model.toggleMark("type:RULE")

        model.applyBulk(model.bulkPlan(CoverageBulkAction.INSTALL, "ungrouped"))

        // Both places were asked, each on its own: the refusal costs the batch that place alone.
        assertEquals(
            listOf(setOf("project:${fixturePath("/tmp/atlas")}"), setOf("project:${fixturePath("/tmp/beacon")}")),
            calls.map { it.second },
        )
        val report = assertNotNull(model.report)
        assertEquals(1, report.written)
        assertEquals(mapOf(SkipReason.MODIFIED to 1), report.skipped)
        // The shortfall is named: "partly done" without a where is not a report.
        assertEquals(listOf("beacon"), report.refused)
        assertEquals(emptySet(), model.marked)
        assertEquals(before + 1, scans)
    }

    @Test fun `a batch that wrote nothing leaves the scan alone and still reports`() = runTest {
        refusing += "project:${fixturePath("/tmp/atlas")}"
        refusing += "project:${fixturePath("/tmp/beacon")}"
        val model = model()
        model.rescan()
        val before = scans
        model.toggleMark("type:RULE")

        model.applyBulk(model.bulkPlan(CoverageBulkAction.INSTALL, "ungrouped"))

        assertEquals(0, assertNotNull(model.report).written)
        assertEquals(before, scans)
        model.dismissReport()
        assertNull(model.report)
    }

    @Test fun `removing a marked row goes out through the same installer`() = runTest {
        val model = model()
        model.rescan()
        model.toggleMark("type:RULE")

        model.applyBulk(model.bulkPlan(CoverageBulkAction.REMOVE, "ungrouped"))

        // Only atlas holds anything of the marked rows, so only atlas is asked.
        assertEquals(listOf(Triple("remove", setOf("project:${fixturePath("/tmp/atlas")}"), listOf("style"))), calls)
    }

    private fun model(agentCount: Int = 0) = CoverageModel(
        repository = repository,
        configStore = configStore,
        agents = emptyList(),
        usageSource = { _, _ ->
            scans++
            (1..agentCount).map {
                LibraryPlaceUsage("agent:$it", "Assistant $it", LibraryPlaceKind.AGENT, emptyMap())
            } + listOf(
                LibraryPlaceUsage(
                    id = "project:${fixturePath("/tmp/atlas")}",
                    name = "atlas",
                    kind = LibraryPlaceKind.PROJECT,
                    installs = mapOf(key to LibraryInstall(InstallStatus.SYNCED)),
                ),
                LibraryPlaceUsage(
                    id = "project:${fixturePath("/tmp/beacon")}",
                    name = "beacon",
                    kind = LibraryPlaceKind.PROJECT,
                    installs = emptyMap(),
                ),
            )
        },
        installer = installer,
    )

    private val installer = object : LibraryPlaceInstaller {
        override fun places(): List<LibraryPlaceRef> = emptyList()

        override fun install(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport {
            calls += Triple("install", placeIds, items.map { it.key.id })
            return answer(placeIds)
        }

        override fun remove(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport {
            calls += Triple("remove", placeIds, items.map { it.key.id })
            return answer(placeIds)
        }

        private fun answer(placeIds: Set<String>): LibraryInstallReport =
            if (placeIds.any { it in refusing }) LibraryInstallReport(skipped = mapOf(SkipReason.MODIFIED to 1))
            else report
    }
}
