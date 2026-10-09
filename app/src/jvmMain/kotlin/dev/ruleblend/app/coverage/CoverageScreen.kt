package dev.ruleblend.app.coverage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SectionHeaderStyle
import kotlinx.coroutines.launch

/** Lets a test reach one agent's visibility box; the panel rows carry no other unique handle. */
internal fun coverageAgentTag(agentId: String) = "coverage-agent-$agentId"

/** One cell by the pair it stands for: the only handle a grid of identical marks can offer. */
internal fun coverageCellTag(rowId: String, columnId: String) = "coverage-cell-$rowId@$columnId"

internal fun coverageRowTag(rowId: String) = "coverage-row-$rowId"

internal fun coverageColumnTag(columnId: String) = "coverage-column-$columnId"

internal fun coveragePlaceTag(columnId: String) = "coverage-place-$columnId"

/** The tick that puts a row into a bulk action; identical boxes need one handle each. */
internal fun coverageMarkTag(rowId: String) = "coverage-mark-$rowId"

/** One control of the bulk bar: `install`, `remove`, `update`, `clear`, `apply`, or a target column. */
internal fun coverageBulkTag(name: String) = "coverage-bulk-$name"

/**
 * The Coverage surface: where every library object sits, aggregated to a grid a person can read.
 *
 * The screen draws [CoverageModel.snapshot] and nothing else. Rows scroll lazily and every row shares
 * one horizontal scroll state with the header, so the 304 × 30 fleet costs a screenful of cells, not
 * a full matrix — opening a row of 200 objects adds lazy items, not a wider drawing.
 *
 * A click writes only where the model says one object meets one place; everything coarser than that
 * is an aggregate, and an aggregate has no single write to perform.
 */
@Composable
fun CoverageScreen(
    model: CoverageModel,
    onOpenLibrary: () -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenResolve: () -> Unit = {},
    onOpenAgentSettings: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    CoverageScreen(
        snapshot = model.snapshot,
        filters = model.filters,
        expansion = model.expansion,
        scanning = model.scanning,
        writing = model.writing,
        notice = model.notice,
        lastScanMillis = model.lastScanMillis,
        onFilters = model::applyFilters,
        onRescan = { scope.launch { model.rescan() } },
        onAgentVisible = { id, visible -> scope.launch { model.setAgentVisible(id, visible) } },
        onToggleRow = model::toggleRow,
        onToggleColumn = model::toggleColumn,
        onSetInstalled = { key, placeId, install -> scope.launch { model.setInstalled(key, placeId, install) } },
        onDismissNotice = model::dismissNotice,
        onOpenLibrary = onOpenLibrary,
        onOpenPlace = onOpenPlace,
        onOpenResolve = onOpenResolve,
        onOpenAgentSettings = onOpenAgentSettings,
        onExpandAllRows = model::expandAllRows,
        onCollapseAllRows = model::collapseAllRows,
        onExpandAllColumns = model::expandAllColumns,
        onCollapseAllColumns = model::collapseAllColumns,
        modifier = modifier,
        marked = model.marked,
        onToggleMark = model::toggleMark,
        onClearMarks = model::clearMarks,
        planOf = model::bulkPlan,
        onApplyBulk = { plan -> scope.launch { model.applyBulk(plan) } },
        report = model.report,
        onDismissReport = model::dismissReport,
    )
}

/** The rendering half, free of the model: one snapshot in, filter, drill-down and write requests out. */
@Composable
fun CoverageScreen(
    snapshot: CoverageSnapshot,
    filters: CoverageFilters,
    scanning: Boolean,
    lastScanMillis: Long?,
    onFilters: (CoverageFilters) -> Unit,
    onRescan: () -> Unit,
    onAgentVisible: (String, Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    expansion: CoverageExpansion = CoverageExpansion(),
    writing: Boolean = false,
    notice: CoverageNotice? = null,
    onToggleRow: (String) -> Unit = {},
    onToggleColumn: (String) -> Unit = {},
    onSetInstalled: (LibraryObjectKey, String, Boolean) -> Unit = { _, _, _ -> },
    onDismissNotice: () -> Unit = {},
    onOpenPlace: (String) -> Unit = {},
    marked: Set<String> = emptySet(),
    onToggleMark: (String) -> Unit = {},
    onClearMarks: () -> Unit = {},
    planOf: (CoverageBulkAction, String?) -> CoverageBulkPlan = { action, _ -> CoverageBulkPlan(action) },
    onApplyBulk: (CoverageBulkPlan) -> Unit = {},
    report: CoverageBulkReport? = null,
    onDismissReport: () -> Unit = {},
    onOpenResolve: () -> Unit = {},
    onOpenAgentSettings: () -> Unit = {},
    onExpandAllRows: () -> Unit = {},
    onCollapseAllRows: () -> Unit = {},
    onExpandAllColumns: () -> Unit = {},
    onCollapseAllColumns: () -> Unit = {},
) {
    val strings = LocalStrings.current
    // The plan a target has been picked for and the confirmation is showing; nothing is written yet.
    var pending by remember { mutableStateOf<CoverageBulkPlan?>(null) }

    Column(modifier.fillMaxSize()) {
        Toolbar(
            snapshot = snapshot,
            filters = filters,
            scanning = scanning,
            writing = writing,
            lastScanMillis = lastScanMillis,
            strings = strings,
            fold = snapshot.fold(expansion),
            onFilters = onFilters,
            onRescan = onRescan,
            onAgentVisible = onAgentVisible,
            onOpenResolve = onOpenResolve,
            onOpenAgentSettings = onOpenAgentSettings,
            onExpandAllRows = onExpandAllRows,
            onCollapseAllRows = onCollapseAllRows,
            onExpandAllColumns = onExpandAllColumns,
            onCollapseAllColumns = onCollapseAllColumns,
        )
        if (notice != null) Notice(notice, strings, onDismissNotice)
        if (report != null) BulkReport(report, strings, onDismissReport)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        // The matrix takes what is left rather than everything: the bulk bar sits under it, and a
        // list that measures itself to the full height would push its own controls off the window.
        Box(Modifier.weight(1f)) {
            when {
                !snapshot.scanned -> State(
                    title = strings.homeNotScannedTitle,
                    text = strings.coverageNotScannedText,
                    actionLabel = strings.homeScanNow,
                    onAction = onRescan,
                )

                snapshot.places == 0 -> State(strings.homeNoPlacesTitle, strings.homeNoPlacesText)

                snapshot.objects == 0 -> State(
                    title = strings.homeEmptyLibraryTitle,
                    text = strings.homeEmptyLibraryText,
                    actionLabel = strings.homeOpenLibrary,
                    onAction = onOpenLibrary,
                )

                snapshot.rows.isEmpty() -> State(strings.coverageNoRowsTitle, strings.coverageNoRowsText)

                else -> Matrix(
                    snapshot = snapshot,
                    expansion = expansion,
                    strings = strings,
                    marked = marked,
                    writing = writing || scanning,
                    onToggleRow = onToggleRow,
                    onToggleColumn = onToggleColumn,
                    onToggleMark = onToggleMark,
                    onSetInstalled = onSetInstalled,
                    onOpenPlace = onOpenPlace,
                )
            }
        }
        Legend(strings)
        if (marked.isNotEmpty()) {
            BulkBar(
                snapshot = snapshot,
                marked = marked,
                strings = strings,
                writing = writing,
                onClearMarks = onClearMarks,
                onPlan = { action, columnId -> pending = planOf(action, columnId) },
            )
        }
    }
    val plan = pending
    if (plan != null) {
        BulkPlanDialog(
            plan = plan,
            snapshot = snapshot,
            strings = strings,
            onDismiss = { pending = null },
            onConfirm = { pending = null; onApplyBulk(plan) },
        )
    }
}

/** What the last click refused to do, said once and dismissible; it never replaces the matrix. */
@Composable
private fun Notice(notice: CoverageNotice, strings: Strings, onDismiss: () -> Unit) {
    CoverageBanner(
        text = when (notice) {
            CoverageNotice.MODIFIED -> strings.coverageNoticeModified
            CoverageNotice.OUT_OF_SCOPE -> strings.coverageNoticeScope
            CoverageNotice.UNSUPPORTED -> strings.coverageNoticeUnsupported
            CoverageNotice.FAILED -> strings.coverageNoticeFailed
        },
        color = RuleblendTheme.extraColors.badgeModFg,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun CoverageBanner(text: String, color: Color, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
        CompactOutlinedButton(onClick = onDismiss) {
            Text(strings.coverageNoticeDismiss, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun State(title: String, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = SectionHeaderStyle)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Row(Modifier.padding(top = 6.dp)) {
                CompactOutlinedButton(onClick = onAction) {
                    Text(actionLabel, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---- wording ----
