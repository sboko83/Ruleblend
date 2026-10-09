package dev.ruleblend.app.coverage

import dev.ruleblend.app.theme.RuleblendTooltip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.FoldButtons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.theme.IconActionButton
import androidx.compose.foundation.layout.PaddingValues
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.ToggleChip

@Composable
internal fun Toolbar(
    snapshot: CoverageSnapshot,
    filters: CoverageFilters,
    scanning: Boolean,
    writing: Boolean,
    lastScanMillis: Long?,
    strings: Strings,
    fold: CoverageFold,
    onFilters: (CoverageFilters) -> Unit,
    onRescan: () -> Unit,
    onAgentVisible: (String, Boolean) -> Unit,
    onOpenResolve: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onExpandAllRows: () -> Unit,
    onCollapseAllRows: () -> Unit,
    onExpandAllColumns: () -> Unit,
    onCollapseAllColumns: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val faint = RuleblendTheme.extraColors.faint
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.coverageCorner(snapshot.objects, snapshot.agentPlaces, snapshot.projectPlaces),
                    style = MaterialTheme.typography.bodyLarge, color = faint)
                if (snapshot.scanned && snapshot.conflicts.count > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.coverageChipConflicts(snapshot.conflicts.count, snapshot.conflicts.agents, snapshot.conflicts.projects),
                            style = MaterialTheme.typography.bodyLarge, color = RuleblendTheme.extraColors.badgeModFg,
                            modifier = Modifier.weight(1f, fill = false))
                        CompactOutlinedButton(onOpenResolve, Modifier.testTag("coverage-open-resolve")) {
                            Text(strings.coverageOpenResolve)
                        }
                    }
                }
                if (snapshot.scanned && snapshot.updates.count > 0) {
                    TextButton(
                        onClick = { onFilters(filters.copy(needsAttention = true)) },
                        modifier = Modifier.testTag("coverage-updates"),
                    ) {
                        Text(strings.coverageChipUpdates(snapshot.updates.count, snapshot.updates.agents, snapshot.updates.projects),
                            color = RuleblendTheme.extraColors.badgeUpdFg)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val note = when {
                    writing -> strings.coverageWriting
                    scanning -> strings.homeScanning
                    lastScanMillis != null -> strings.homeScanDuration(lastScanMillis)
                    else -> null
                }
                if (note != null) Text(note, style = MaterialTheme.typography.bodyLarge, color = faint)
                IconActionButton(RuleblendTheme.icons.refresh, strings.homeRescan, onRescan,
                    enabled = !scanning && !writing)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.coverageRowsLabel, style = MaterialTheme.typography.labelMedium, color = faint)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryObjectKind.entries.filter { it != LibraryObjectKind.GROUP && it != LibraryObjectKind.PROFILE }.forEach { kind ->
                        CoverageChip(kind.label(strings), filters.kinds.isEmpty() || kind in filters.kinds,
                            { onFilters(filters.copy(kinds = filters.kinds.toggled(kind))) },
                            Modifier.testTag("coverage-filter-${kind.name.lowercase()}"))
                    }
                }
                VerticalDivider(Modifier.height(32.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverageChip(strings.coverageOnlyInstalled, filters.onlyInstalled,
                        { onFilters(filters.copy(onlyInstalled = !filters.onlyInstalled)) },
                        Modifier.testTag("coverage-filter-installed"))
                    CoverageChip(strings.coverageNeedsAttention, filters.needsAttention,
                        { onFilters(filters.copy(needsAttention = !filters.needsAttention)) },
                        Modifier.testTag("coverage-filter-attention"))
                }
            }
            FoldButtons(onExpandAllRows.takeIf { fold.rowsExpandable }, onCollapseAllRows.takeIf { fold.rowsCollapsible },
                strings.coverageExpandAllRows, strings.coverageCollapseAllRows, "coverage-rows")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.coverageColumnsLabel, style = MaterialTheme.typography.labelMedium, color = faint)
                Box {
                    CompactOutlinedButton({ menuOpen = !menuOpen }, Modifier.testTag("coverage-agents-panel")) {
                        Text("${strings.coverageAgentsPanel} ▾")
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        AgentsPanel(snapshot.agents, strings, onAgentVisible)
                        DropdownMenuItem(
                            text = { Text(strings.coverageAgentsSettings) },
                            onClick = { menuOpen = false; onOpenAgentSettings() },
                            modifier = Modifier.testTag("coverage-agent-settings"),
                        )
                    }
                }
            }
            FoldButtons(onExpandAllColumns.takeIf { fold.columnsExpandable }, onCollapseAllColumns.takeIf { fold.columnsCollapsible },
                strings.coverageExpandAllColumns, strings.coverageCollapseAllColumns, "coverage-columns")
        }
    }
}

@Composable
internal fun Legend(strings: Strings) {
    val extras = RuleblendTheme.extraColors
    FlowRow(
        Modifier.fillMaxWidth().testTag("coverage-legend").padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendItem(strings.coverageLegendSynced) { StatusDot(extras.badgeOkFg) }
        LegendItem(strings.coverageLegendUpdate) { StatusDot(extras.badgeUpdFg) }
        LegendItem(strings.coverageLegendModified) { StatusDot(extras.badgeModFg) }
        LegendItem(strings.coverageLegendBar) {
            MiniBar(CoverageCell(installed = 7, members = 10, synced = 4, updates = 2, modified = 1), Modifier.width(48.dp))
        }
        LegendItem(strings.coverageLegendFraction) {
            Text("3/10", style = MaterialTheme.typography.labelMedium, color = extras.faint)
        }
        RuleblendTooltip(strings.coverageLegendOutOfScope) {
            LegendItem(strings.coverageLegendOutOfScopeShort) {
                Text("—", style = MaterialTheme.typography.labelMedium, color = extras.faint)
            }
        }
        LegendItem(strings.coverageLegendLeafInstalled) { StatusDot(extras.faint) }
        LegendItem(strings.coverageLegendLeafMissing) {
            Text("+", style = MaterialTheme.typography.labelMedium, color = extras.faint)
        }
    }
}

@Composable
private fun LegendItem(text: String, mark: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        mark()
        Text(text, style = MaterialTheme.typography.labelMedium, color = RuleblendTheme.extraColors.faint)
    }
}

/** Hidden assistants stay in this menu so visibility can be restored without leaving Coverage. */
@Composable
internal fun AgentsPanel(
    agents: List<CoverageAgent>,
    strings: Strings,
    onAgentVisible: (String, Boolean) -> Unit,
) {
    Column(Modifier.widthIn(max = 420.dp).padding(vertical = 6.dp)) {
        agents.forEach { agent ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SquareCheckbox(checked = agent.visible,
                    onCheckedChange = { onAgentVisible(agent.id, it) },
                    modifier = Modifier.testTag(coverageAgentTag(agent.id)))
                Text(agent.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (!agent.skills) Text(strings.coverageAgentNoSkills,
                    style = MaterialTheme.typography.bodyLarge, color = RuleblendTheme.extraColors.faint)
            }
        }
        Text(strings.coverageAgentsNote, style = MaterialTheme.typography.bodyLarge,
            color = RuleblendTheme.extraColors.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/**
 * Chips carry "every type" as the empty set, so turning the last one back on is the same state as
 * never having filtered. Turning the last one *off* is refused: an empty matrix is not a filter.
 */
private fun Set<LibraryObjectKind>.toggled(kind: LibraryObjectKind): Set<LibraryObjectKind> {
    val all = setOf(LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.SKILL, LibraryObjectKind.MCP)
    val current = ifEmpty { all }
    val next = if (kind in current) current - kind else current + kind
    return when {
        next.isEmpty() -> this
        next == all -> emptySet()
        else -> next
    }
}

@Composable
private fun CoverageChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ToggleChip(
        label, on, onClick, modifier,
        textStyle = MaterialTheme.typography.bodyLarge,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
    )
}
