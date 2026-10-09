package dev.ruleblend.app.resolve

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.compare.LineDiffText
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.core.integration.ConflictLineKind
import kotlinx.coroutines.launch

/** One entry of the class list; the three rows are otherwise indistinguishable to a test. */
internal fun resolveClassTag(cls: ResolveClass) = "resolve-class-${cls.name.lowercase()}"

/** One occurrence. A legacy row has no block, so the file is what makes it unique in its place. */
internal fun resolveRowTag(row: ResolveRow) =
    "resolve-row-${row.placeId}@${row.file}${if (row.hasBlock) "#${row.blockId}" else ""}"

/** The diff body of an occurrence, present only while the row is open. */
internal fun resolveDiffTag(row: ResolveRow) = "resolve-diff-${row.placeId}@${row.blockId}"

/** The tick of an occurrence; keyed like the decision it carries, not like the row that shows it. */
internal fun resolveMarkTag(row: ResolveRow) = "resolve-mark-${row.key}"

/** The per-row exception to the base strategy. */
internal fun resolveOverrideTag(row: ResolveRow) = "resolve-override-${row.key}"

/** One choice of the strategy panel, and the batch controls that read it. */
internal fun resolveStrategyTag(strategy: ResolveStrategy) = "resolve-strategy-${strategy.name.lowercase()}"

private val ClassesWidth = 230.dp
private val PanelWidth = 300.dp

/**
 * The Resolve surface: every conflict of the fleet, sorted into the classes core can tell apart, and
 * the batch that settles the resolvable ones.
 *
 * The classification, the diff and the refusal to attribute an ambiguous run were all decided during
 * the scan, so no file is read and no comparison is redone while scrolling. Only the hand-edited class
 * offers a strategy: the other two carry an explanation and a way into Place instead, which is the
 * whole reason they are separate classes.
 */
@Composable
fun ResolveScreen(
    model: ResolveModel,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    ResolveScreen(
        snapshot = model.snapshot,
        selected = model.selected,
        scanning = model.scanning,
        writing = model.writing,
        lastScanMillis = model.lastScanMillis,
        marked = model.marked,
        base = model.base,
        overrides = model.overrides,
        plan = model.plan,
        report = model.report,
        onSelect = model::select,
        onRescan = { scope.launch { model.rescan() } },
        onOpenPlace = onOpenPlace,
        onToggleMark = model::toggleMark,
        onMarkAll = model::markAll,
        onBase = model::chooseBase,
        onOverride = model::setOverride,
        onApply = { scope.launch { model.apply() } },
        onDismissReport = model::dismissReport,
        modifier = modifier,
    )
}

/** The rendering half, free of the model: one snapshot and one plan in, the batch's inputs out. */
@Composable
fun ResolveScreen(
    snapshot: ResolveSnapshot,
    selected: ResolveClass,
    scanning: Boolean,
    lastScanMillis: Long?,
    onSelect: (ResolveClass) -> Unit,
    onRescan: () -> Unit,
    onOpenPlace: (String) -> Unit,
    writing: Boolean = false,
    marked: Set<String> = emptySet(),
    base: ResolveStrategy = ResolveStrategy.RESTORE,
    overrides: Map<String, ResolveStrategy> = emptyMap(),
    plan: ResolvePlan = ResolvePlan(),
    report: ResolveReport? = null,
    onToggleMark: (String) -> Unit = {},
    onMarkAll: (Boolean) -> Unit = {},
    onBase: (ResolveStrategy) -> Unit = {},
    onOverride: (String, ResolveStrategy?) -> Unit = { _, _ -> },
    onApply: () -> Unit = {},
    onDismissReport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(strings.resolveTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                when {
                    !snapshot.scanned -> ""
                    else -> strings.resolveScanned(snapshot.total)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            lastScanMillis?.let {
                Text(
                    strings.homeScanDuration(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = RuleblendTheme.extraColors.faint,
                )
            }
            CompactOutlinedButton(
                onClick = onRescan,
                enabled = !scanning && !writing,
                modifier = Modifier.testTag("resolve-rescan"),
            ) {
                Text(if (scanning) strings.homeScanning else strings.homeRescan)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxSize()) {
            ClassColumn(snapshot, selected, strings, onSelect)
            HorizontalDividerVertical()
            OccurrenceTable(
                snapshot = snapshot,
                selected = selected,
                strings = strings,
                marked = marked,
                base = base,
                overrides = overrides,
                enabled = !writing && !scanning,
                onOpenPlace = onOpenPlace,
                onToggleMark = onToggleMark,
                onMarkAll = onMarkAll,
                onOverride = onOverride,
                modifier = Modifier.weight(1f),
            )
            HorizontalDividerVertical()
            if (selected == ResolveClass.HAND_EDITED) {
                StrategyPanel(base, plan, report, writing, strings, onBase, onApply, onDismissReport)
            } else {
                ExplainPanel(selected, strings)
            }
        }
    }
}

/** A vertical rule between columns; the surface uses the same outline colour everywhere. */
@Composable
private fun HorizontalDividerVertical() {
    Surface(
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.width(1.dp).fillMaxHeight(),
        content = {},
    )
}

/**
 * The classes, always all three. A class at zero stays listed and disabled-looking rather than
 * disappearing: the list is what tells a reader which conflicts Ruleblend distinguishes at all.
 */
@Composable
private fun ClassColumn(
    snapshot: ResolveSnapshot,
    selected: ResolveClass,
    strings: Strings,
    onSelect: (ResolveClass) -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    Column(
        Modifier.width(ClassesWidth).fillMaxHeight().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(strings.resolveClasses.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(bottom = 4.dp))
        snapshot.classes.forEach { info ->
            val active = info.cls == selected
            Surface(
                color = if (active) extras.accentBackground else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(RuleblendTheme.dimensions.cornerRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(info.cls) }
                    .testTag(resolveClassTag(info.cls)),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        info.cls.label(strings),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        info.total.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (info.total == 0) extras.faint else extras.warning,
                    )
                }
            }
        }
    }
}

/** Occurrences of the selected class, grouped by place — the unit a fix is applied to. */
@Composable
private fun OccurrenceTable(
    snapshot: ResolveSnapshot,
    selected: ResolveClass,
    strings: Strings,
    marked: Set<String>,
    base: ResolveStrategy,
    overrides: Map<String, ResolveStrategy>,
    enabled: Boolean,
    onOpenPlace: (String) -> Unit,
    onToggleMark: (String) -> Unit,
    onMarkAll: (Boolean) -> Unit,
    onOverride: (String, ResolveStrategy?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = snapshot.groups(selected)
    if (!snapshot.scanned || groups.isEmpty()) {
        Column(modifier.fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (!snapshot.scanned) strings.resolveNotScannedTitle else strings.resolveNothingTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (!snapshot.scanned) strings.resolveNotScannedText else strings.resolveNothingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val keys = if (selected == ResolveClass.HAND_EDITED) resolveKeys(groups) else emptySet()
    LazyColumn(modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
        if (keys.isNotEmpty()) {
            item(key = "select-all") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SquareCheckbox(
                        checked = keys.all { it in marked },
                        onCheckedChange = onMarkAll,
                        enabled = enabled,
                        modifier = Modifier.testTag("resolve-mark-all"),
                    )
                    Text(strings.resolveSelectAll, style = MaterialTheme.typography.labelMedium)
                    Text(
                        strings.resolveMarked(keys.count { it in marked }),
                        style = MaterialTheme.typography.labelMedium,
                        color = RuleblendTheme.extraColors.faint,
                    )
                }
            }
        }
        groups.forEach { group ->
            item(key = "group-${group.placeId}-${selected.name}") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(group.placeName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        strings.resolveOccurrences(group.rows.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = RuleblendTheme.extraColors.faint,
                    )
                }
            }
            items(group.rows.size, key = { index -> resolveRowTag(group.rows[index]) }) { index ->
                val row = group.rows[index]
                OccurrenceRow(
                    row = row,
                    selected = selected,
                    strings = strings,
                    marked = row.key in marked,
                    base = base,
                    override = overrides[row.key],
                    enabled = enabled,
                    onOpenPlace = onOpenPlace,
                    onToggleMark = onToggleMark,
                    onOverride = onOverride,
                )
            }
        }
    }
}

/**
 * One occurrence. Clicking a hand-edited row opens its diff — the whole reason the class exists is
 * that the answer depends on what the edit says.
 */
@Composable
private fun OccurrenceRow(
    row: ResolveRow,
    selected: ResolveClass,
    strings: Strings,
    marked: Boolean,
    base: ResolveStrategy,
    override: ResolveStrategy?,
    enabled: Boolean,
    onOpenPlace: (String) -> Unit,
    onToggleMark: (String) -> Unit,
    onOverride: (String, ResolveStrategy?) -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    var open by remember(row.placeId, row.blockId, row.file) { mutableStateOf(false) }
    val expandable = row.diff.isNotEmpty()
    val resolvable = selected == ResolveClass.HAND_EDITED
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(RuleblendTheme.dimensions.cornerRadius),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag(resolveRowTag(row)),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (expandable) Modifier.clickable { open = !open } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (resolvable) {
                    SquareCheckbox(
                        checked = marked,
                        onCheckedChange = { onToggleMark(row.key) },
                        enabled = enabled,
                        modifier = Modifier.testTag(resolveMarkTag(row)),
                    )
                }
                Text(
                    row.file,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                )
                Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (row.hasBlock) row.blockName else strings.resolveLegacyMarkers,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.hasBlock) {
                        Text(
                            strings.resolveVersion(row.version),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = extras.faint,
                        )
                    }
                }
                Text(
                    when (selected) {
                        ResolveClass.HAND_EDITED -> strings.resolveDrift(row.added, row.removed)
                        ResolveClass.AMBIGUOUS -> strings.resolveAmbiguousRow
                        ResolveClass.LEGACY -> strings.resolveLegacyRow
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = if (selected == ResolveClass.HAND_EDITED) FontFamily.Monospace else FontFamily.Default,
                    color = if (selected == ResolveClass.HAND_EDITED) extras.warning else extras.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Shares the row with the block name instead of measuring first: a long status
                    // must never squeeze the name it describes down to nothing.
                    modifier = Modifier.weight(1f),
                )
                if (resolvable) {
                    OverrideMenu(row, base, override, enabled && marked, strings, onOverride)
                }
                CompactOutlinedButton(onClick = { onOpenPlace(row.placeId) }) {
                    Text(strings.resolveOpenPlace)
                }
            }
            if (open && expandable) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                DiffBody(row)
            }
        }
    }
}

/**
 * The row's exception to the base strategy. Inheriting is a listed choice rather than an implicit
 * state: a row that says "Inherit" keeps following the panel, and one that names a strategy keeps it
 * when the panel changes — which is the only way a mixed batch can be read off the table.
 */
@Composable
private fun OverrideMenu(
    row: ResolveRow,
    base: ResolveStrategy,
    override: ResolveStrategy?,
    enabled: Boolean,
    strings: Strings,
    onOverride: (String, ResolveStrategy?) -> Unit,
) {
    var open by remember(row.key) { mutableStateOf(false) }
    Box {
        CompactOutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.testTag(resolveOverrideTag(row)),
        ) {
            // An inheriting row names the strategy it will follow, faintly: what a row is about to do
            // must be readable from the row, not reconstructed from the panel.
            Text(
                (override ?: base).label(strings),
                style = MaterialTheme.typography.labelMedium,
                color = if (override == null) RuleblendTheme.extraColors.faint else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(strings.resolveStrategyInherit, style = MaterialTheme.typography.labelMedium) },
                onClick = {
                    open = false
                    onOverride(row.key, null)
                },
            )
            ResolveStrategy.entries.forEach { strategy ->
                DropdownMenuItem(
                    text = { Text(strategy.label(strings), style = MaterialTheme.typography.labelMedium) },
                    onClick = {
                        open = false
                        onOverride(row.key, strategy)
                    },
                    modifier = Modifier.testTag("${resolveOverrideTag(row)}-${strategy.name.lowercase()}"),
                )
            }
        }
    }
}

/**
 * The batch: one strategy for everything marked, the exceptions the table already carries, and the
 * sentence the plan says before anything is written. After a run the same column holds the report —
 * a partial batch is the normal outcome on a real fleet, so it is stated rather than smoothed into
 * "done".
 */
@Composable
private fun StrategyPanel(
    base: ResolveStrategy,
    plan: ResolvePlan,
    report: ResolveReport?,
    writing: Boolean,
    strings: Strings,
    onBase: (ResolveStrategy) -> Unit,
    onApply: () -> Unit,
    onDismissReport: () -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    Column(
        Modifier.width(PanelWidth).fillMaxHeight().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.resolveStrategy.uppercase(), style = SectionHeaderStyle)
        if (report != null) {
            ReportBody(report, strings, onDismissReport)
            return@Column
        }
        ResolveStrategy.entries.forEach { strategy ->
            val active = strategy == base
            Surface(
                color = if (active) extras.accentBackground else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(RuleblendTheme.dimensions.cornerRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBase(strategy) }
                    .testTag(resolveStrategyTag(strategy)),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(
                        strategy.label(strings),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        strategy.hint(strings),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(strings.resolvePlanTitle.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp))
        Text(
            if (plan.empty && plan.skipped == 0) strings.resolvePlanNothing
            else strings.resolvePlanText(plan.restores, plan.saves, plan.places.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("resolve-plan"),
        )
        if (plan.skipped > 0) {
            Text(
                strings.resolvePlanSkipped(plan.skipped),
                style = MaterialTheme.typography.labelMedium,
                color = extras.faint,
            )
        }
        CompactButton(
            onClick = onApply,
            enabled = !plan.empty && !writing,
            modifier = Modifier.testTag("resolve-apply"),
        ) {
            Text(if (writing) strings.resolveApplying else strings.resolveApply(plan.writes))
        }
        Text(strings.resolveSafety, style = MaterialTheme.typography.labelMedium, color = extras.faint)
    }
}

/** What the batch did, per outcome. A place that took nothing is named, not counted. */
@Composable
private fun ReportBody(report: ResolveReport, strings: Strings, onDismiss: () -> Unit) {
    Text(
        strings.resolveDone,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("resolve-report"),
    )
    buildList {
        if (report.restored > 0) add(strings.resolveDoneRestored(report.restored))
        if (report.saved > 0) add(strings.resolveDoneSaved(report.saved))
        if (report.skipped > 0) add(strings.resolveDoneSkipped(report.skipped))
        if (report.failed.isNotEmpty()) add(strings.resolveDoneFailed(report.failed.joinToString(", ")))
    }.forEach { line ->
        Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    CompactOutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("resolve-report-dismiss")) {
        Text(strings.coverageNoticeDismiss, style = MaterialTheme.typography.labelMedium)
    }
    Text(
        strings.resolveSafety,
        style = MaterialTheme.typography.labelMedium,
        color = RuleblendTheme.extraColors.faint,
    )
}

/** The library body on the minus side, what the file holds on the plus side. */
@Composable
private fun DiffBody(row: ResolveRow) {
    val scroll = rememberScrollState()
    val (libraryContent, fileContent) = row.diff.textSides()
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(resolveDiffTag(row)),
    ) {
        LineDiffText(libraryContent, fileContent, contextColor = RuleblendTheme.extraColors.faint)
    }
}

/** What the selected class means and what can be done about it — including "nothing, by design". */
@Composable
private fun ExplainPanel(selected: ResolveClass, strings: Strings) {
    Column(
        Modifier.width(PanelWidth).fillMaxHeight().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(selected.label(strings).uppercase(), style = SectionHeaderStyle)
        Text(
            selected.explanation(strings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            selected.nextStep(strings),
            style = MaterialTheme.typography.labelMedium,
            color = RuleblendTheme.extraColors.faint,
        )
    }
}

private fun List<dev.ruleblend.core.integration.ConflictLine>.textSides(): Pair<String, String> =
    filter { it.kind != ConflictLineKind.ADDED }.joinToString("\n") { it.text } to
        filter { it.kind != ConflictLineKind.REMOVED }.joinToString("\n") { it.text }

private fun ResolveClass.label(strings: Strings): String = when (this) {
    ResolveClass.HAND_EDITED -> strings.resolveClassHandEdited
    ResolveClass.AMBIGUOUS -> strings.resolveClassAmbiguous
    ResolveClass.LEGACY -> strings.resolveClassLegacy
}

private fun ResolveClass.explanation(strings: Strings): String = when (this) {
    ResolveClass.HAND_EDITED -> strings.resolveExplainHandEdited
    ResolveClass.AMBIGUOUS -> strings.resolveExplainAmbiguous
    ResolveClass.LEGACY -> strings.resolveExplainLegacy
}

/** Only the two classes without a strategy reach [ExplainPanel]; the third one gets the batch instead. */
private fun ResolveClass.nextStep(strings: Strings): String = when (this) {
    ResolveClass.AMBIGUOUS -> strings.resolveNextAmbiguous
    ResolveClass.HAND_EDITED, ResolveClass.LEGACY -> strings.resolveNextLegacy
}

private fun ResolveStrategy.label(strings: Strings): String = when (this) {
    ResolveStrategy.RESTORE -> strings.resolveStrategyRestore
    ResolveStrategy.SAVE_AS_VERSION -> strings.resolveStrategySave
    ResolveStrategy.SKIP -> strings.resolveStrategySkip
}

private fun ResolveStrategy.hint(strings: Strings): String = when (this) {
    ResolveStrategy.RESTORE -> strings.resolveStrategyRestoreHint
    ResolveStrategy.SAVE_AS_VERSION -> strings.resolveStrategySaveHint
    ResolveStrategy.SKIP -> strings.resolveStrategySkipHint
}
