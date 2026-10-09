package dev.ruleblend.app.coverage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryInstallReport
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceInstaller
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.LibraryUsageSource
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.SkipReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val AGENTS_OPEN_LIMIT = 4

/**
 * Why one cell click changed nothing. Silence would read as "done", which is the failure mode that
 * makes a click-to-install grid untrustworthy; every refusal says which rule refused.
 */
enum class CoverageNotice { OUT_OF_SCOPE, UNSUPPORTED, MODIFIED, FAILED }

/** A report the click can act on: nothing written means something refused it. */
private fun LibraryInstallReport?.notice(): CoverageNotice? = when {
    this == null -> CoverageNotice.FAILED
    written > 0 -> null
    failed > 0 -> CoverageNotice.FAILED
    skipped.containsKey(SkipReason.MODIFIED) -> CoverageNotice.MODIFIED
    skipped.containsKey(SkipReason.OUT_OF_SCOPE) -> CoverageNotice.OUT_OF_SCOPE
    skipped.containsKey(SkipReason.UNSUPPORTED) -> CoverageNotice.UNSUPPORTED
    else -> CoverageNotice.FAILED
}

/**
 * State of the Coverage surface: one aggregated matrix over the same scan Home and Library read.
 *
 * The scan is the expensive part, so it runs on request and its result is kept. Filters and the
 * agent grouping re-aggregate that kept scan — changing a checkbox never touches disk, which is what
 * keeps a 304 × 30 fleet responsive.
 *
 * Agent visibility goes to the same `hiddenAgents` Home writes: a hidden agent stops being a place
 * everywhere, not just in this matrix. A cell click writes through [LibraryPlaceInstaller] — the very
 * services Place and the Library bulk install use, so no third write path exists to disagree with them.
 * A bulk action is that same click repeated over a resolved plan, one place at a time.
 */
class CoverageModel(
    private val repository: LibraryRepository,
    private val configStore: ConfigStore,
    private val agents: List<AgentAdapter>,
    private val usageSource: LibraryUsageSource,
    private val installer: LibraryPlaceInstaller = LibraryPlaceInstaller.None,
) {
    var snapshot by mutableStateOf(CoverageSnapshot.EMPTY)
        private set

    var filters by mutableStateOf(CoverageFilters())
        private set

    var expansion by mutableStateOf(CoverageExpansion())
        private set

    var scanning by mutableStateOf(false)
        private set

    var writing by mutableStateOf(false)
        private set

    /** What the last cell click refused or failed to do; `null` once it simply worked. */
    var notice by mutableStateOf<CoverageNotice?>(null)
        private set

    /** Rows ticked for a bulk action, by row id; cleared once one has been applied. */
    var marked by mutableStateOf(emptySet<String>())
        private set

    /** What the last bulk action did, kept until dismissed: a partial batch has to stay readable. */
    var report by mutableStateOf<CoverageBulkReport?>(null)
        private set

    /** Wall-clock duration of the last scan, in milliseconds; `null` until one finishes. */
    var lastScanMillis by mutableStateOf<Long?>(null)
        private set

    private var scan: Scan? = null
    private var expansionInitialized = false

    suspend fun rescan() {
        if (scanning) return
        scanning = true
        try {
            val started = System.nanoTime()
            val fresh = withContext(Dispatchers.IO) { runCatching { FileReadScope.reading { read() } }.getOrNull() } ?: return
            lastScanMillis = (System.nanoTime() - started) / 1_000_000
            scan = fresh
            if (!expansionInitialized) {
                expansionInitialized = true
                val collapsed = fresh.matrix(filters, CoverageExpansion())
                expansion = CoverageExpansion(
                    rows = collapsed.rows
                        .filter { fresh.groupsExpandedByDefault && it.kind == CoverageRowKind.GROUP && it.expandable }
                        .mapTo(mutableSetOf()) { it.id },
                    columns = collapsed.columns
                        .filter { it.id == "agents" && it.expandable && it.places <= AGENTS_OPEN_LIMIT }
                        .mapTo(mutableSetOf()) { it.id },
                )
            }
            snapshot = fresh.matrix(filters, expansion)
        } finally {
            scanning = false
        }
    }

    /** Re-aggregates the scan in memory: a filter is a view of the same reading, not a new one. */
    fun applyFilters(value: CoverageFilters) {
        filters = value
        reaggregate()
    }

    /** Opens or closes one aggregate row. Costs no reading: the objects are already in the scan. */
    fun toggleRow(id: String) {
        expansion = expansion.toggleRow(id)
        reaggregate()
    }

    /** Opens or closes one column into its places, in the column's own position. */
    fun toggleColumn(id: String) {
        expansion = expansion.toggleColumn(id)
        reaggregate()
    }

    fun expandAllRows() {
        val collapsed = scan?.matrix(filters, CoverageExpansion()) ?: return
        expansion = expansion.copy(rows = collapsed.rows.filter { it.expandable }.mapTo(mutableSetOf()) { it.id })
        reaggregate()
    }

    fun collapseAllRows() {
        expansion = expansion.copy(rows = emptySet())
        reaggregate()
    }

    fun expandAllColumns() {
        val collapsed = scan?.matrix(filters, CoverageExpansion()) ?: return
        expansion = expansion.copy(columns = collapsed.columns.filter { it.expandable }.mapTo(mutableSetOf()) { it.id })
        reaggregate()
    }

    fun collapseAllColumns() {
        expansion = expansion.copy(columns = emptySet())
        reaggregate()
    }

    fun dismissNotice() {
        notice = null
    }

    fun toggleMark(rowId: String) {
        marked = if (rowId in marked) marked - rowId else marked + rowId
    }

    fun clearMarks() {
        marked = emptySet()
    }

    fun dismissReport() {
        report = null
    }

    /** The objects behind the ticked rows, deduplicated across a group and its members. */
    val markedKeys: List<LibraryObjectKey> get() = coverageMarkedKeys(snapshot.rows, marked)

    /**
     * What [action] aimed at [columnId] would write, resolved against the scan in memory. Building a
     * plan reads nothing and writes nothing: it is the sentence the confirmation shows before the
     * batch runs, which is the only way "install into this set" can be an informed press.
     */
    fun bulkPlan(action: CoverageBulkAction, columnId: String?): CoverageBulkPlan {
        val current = scan ?: return CoverageBulkPlan(action)
        val column = columnId?.let { id -> snapshot.columns.find { it.id == id } }
        if (action != CoverageBulkAction.UPDATE && column == null) return CoverageBulkPlan(action)
        return coverageBulkPlan(action, markedKeys, column, current.catalog, current.places)
    }

    /**
     * Runs a plan one place at a time. Each step is the same call one cell click makes, so a place
     * that refuses everything asked of it costs the batch that place and nothing more — a mass action
     * that unwinds itself on the first refusal leaves a fleet in a state nobody asked for.
     *
     * A place that was planned for and took nothing is named in the report. The fleet is read again
     * once, at the end, for the same reason a single write re-reads it: the new state is an answer to
     * be looked up, not one to be inferred.
     */
    suspend fun applyBulk(plan: CoverageBulkPlan) {
        if (writing || scanning || plan.empty) return
        writing = true
        notice = null
        report = null
        try {
            val result = withContext(Dispatchers.IO) { runPlan(plan) }
            report = result
            marked = emptySet()
            if (result.written > 0) rescan()
        } finally {
            writing = false
        }
    }

    private fun runPlan(plan: CoverageBulkPlan): CoverageBulkReport {
        val blocks = runCatching { repository.listBlocks() }.getOrDefault(emptyList())
        val skills = runCatching { repository.listSkills() }.getOrDefault(emptyList())
        var written = 0
        var failed = 0
        val skipped = mutableMapOf<SkipReason, Int>()
        val refused = mutableListOf<String>()
        plan.steps.forEach { step ->
            val items = step.keys.mapNotNull { item(it, blocks, skills) }
            val one = runCatching {
                if (plan.action == CoverageBulkAction.REMOVE) installer.remove(items, setOf(step.placeId))
                else installer.install(items, setOf(step.placeId))
            }.getOrElse { LibraryInstallReport(failed = items.size) }
            written += one.written
            failed += one.failed
            one.skipped.forEach { (reason, count) -> skipped[reason] = (skipped[reason] ?: 0) + count }
            if (one.written == 0) refused += step.placeName
        }
        return CoverageBulkReport(plan.action, written, skipped, failed, refused)
    }

    /**
     * Puts one object into one place or takes it out of one place. The pair comes from an opened
     * cell, so a combination the matrix drew as out of scope can never reach this; the installer
     * refuses it a second time anyway, because a stale scan must not become a wrong write.
     *
     * A write invalidates the whole scan, not one cell, so the fleet is read again afterwards: an
     * install can change a group's status elsewhere, and inventing the new state here would be a
     * second answer to "where is this installed".
     */
    suspend fun setInstalled(key: LibraryObjectKey, placeId: String, install: Boolean) {
        if (writing || scanning) return
        writing = true
        notice = null
        try {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val item = item(key, repository.listBlocks(), repository.listSkills())
                        ?: return@runCatching null
                    if (install) installer.install(listOf(item), setOf(placeId))
                    else installer.remove(listOf(item), setOf(placeId))
                }.getOrNull()
            }
            notice = report.notice()
            if (report != null && report.written > 0) rescan()
        } finally {
            writing = false
        }
    }

    /**
     * Resolves a matrix key back into the write the installer speaks; a group is never a cell. The
     * library is passed in rather than read per key, so a batch of two hundred objects reads it once.
     */
    private fun item(
        key: LibraryObjectKey,
        blocks: List<Block>,
        skills: List<Skill>,
    ): InstallItem? = when (key.kind) {
        // A cell install is a standalone claim on the object, so it carries no group tag: removing
        // the group later must not take an object the user installed on its own.
        LibraryObjectKind.RULE -> blocks.find { it.id == key.id }
            ?.let { InstallItem.Rule(it, group = null) }
        LibraryObjectKind.SUBAGENT -> blocks.find { it.id == key.id }?.let(InstallItem::Subagent)
        LibraryObjectKind.MCP -> blocks.find { it.id == key.id }?.let(InstallItem::Mcp)
        LibraryObjectKind.SKILL -> skills.find { it.id == key.id }?.let(InstallItem::SkillCopy)
        LibraryObjectKind.GROUP -> null
        LibraryObjectKind.PROFILE -> null
    }

    private fun reaggregate() {
        scan?.let { snapshot = it.matrix(filters, expansion) }
    }

    /**
     * Shows or hides an agent across the whole app. Hiding drops its global file from the places and
     * its rows from Place, so the matrix has to be read again rather than merely re-aggregated.
     */
    suspend fun setAgentVisible(agentId: String, visible: Boolean) {
        withContext(Dispatchers.IO) {
            configStore.update { config ->
                val hidden = if (visible) config.hiddenAgents - agentId else config.hiddenAgents + agentId
                config.copy(hiddenAgents = hidden.distinct())
            }
        }
        rescan()
    }

    private class Scan(
        val catalog: LibraryCatalog,
        val places: List<LibraryPlaceUsage>,
        val board: PlaceBoard,
        val agents: List<CoverageAgent>,
        val groupsExpandedByDefault: Boolean,
    ) {
        fun matrix(filters: CoverageFilters, expansion: CoverageExpansion) =
            coverageMatrix(catalog, places, board, agents, filters, expansion)
    }

    private fun read(): Scan {
        val blocks = repository.listBlocks()
        val skills = repository.listSkills()
        val config = configStore.load()
        return Scan(
            catalog = LibraryCatalog.build(blocks, repository.listGroups(), skills, config.ruleScopes),
            places = usageSource.scan(blocks, skills),
            board = config.places,
            groupsExpandedByDefault = config.groupsExpandedByDefault,
            // Hidden agents are no longer places, so they cannot come from the scan; the panel lists
            // them from the adapters, as Settings does, to allow making them visible again.
            agents = agents.filter { it.isAvailable() }.map {
                CoverageAgent(
                    id = it.id,
                    name = it.name,
                    visible = it.id !in config.hiddenAgents,
                    skills = it.globalSkillsDirectory() != null,
                )
            },
        )
    }
}
