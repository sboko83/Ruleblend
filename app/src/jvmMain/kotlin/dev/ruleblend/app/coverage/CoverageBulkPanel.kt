package dev.ruleblend.app.coverage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.generated.resources.Res
import dev.ruleblend.app.generated.resources.close
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.usecase.SkipReason

/**
 * The bulk bar: what is marked, and the three things that can be done with it. It appears only with
 * something marked and never covers the matrix — the rows a batch is about have to stay visible while
 * its target is chosen.
 *
 * Install and remove ask which column first, because their reach is a choice; update does not, since
 * "everywhere" is the whole fleet by definition. Neither writes here: both hand a plan to the
 * confirmation, which is where the count of writes is seen before it happens.
 */
@Composable
internal fun BulkBar(
    snapshot: CoverageSnapshot,
    marked: Set<String>,
    strings: Strings,
    writing: Boolean,
    onClearMarks: () -> Unit,
    onPlan: (CoverageBulkAction, String?) -> Unit,
) {
    var targetsFor by remember { mutableStateOf<CoverageBulkAction?>(null) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    FlowRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            strings.coverageBulkSelected(coverageMarkedKeys(snapshot.rows, marked).size),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Box {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconActionButton(
                    icon = RuleblendTheme.icons.install,
                    label = strings.coverageBulkInstall,
                    onClick = { targetsFor = CoverageBulkAction.INSTALL },
                    enabled = !writing,
                    modifier = Modifier.testTag(coverageBulkTag("install")),
                )
                IconActionButton(
                    icon = RuleblendTheme.icons.delete,
                    label = strings.coverageBulkRemove,
                    onClick = { targetsFor = CoverageBulkAction.REMOVE },
                    enabled = !writing,
                    modifier = Modifier.testTag(coverageBulkTag("remove")),
                )
            }
            DropdownMenu(expanded = targetsFor != null, onDismissRequest = { targetsFor = null }) {
                snapshot.columns.bands(strings).forEach { band ->
                    DropdownMenuItem(
                        text = { Text(band.title, style = MaterialTheme.typography.labelMedium,
                            color = RuleblendTheme.extraColors.faint) },
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.testTag(coverageBulkTag("band-${band.id}")),
                    )
                    band.columns.forEach { column ->
                        DropdownMenuItem(
                            text = { Text(column.bulkTargetTitle(band), style = MaterialTheme.typography.bodyLarge) },
                            trailingIcon = if (column.placeId == null) {
                                { Text(
                                    if (band.kind == CoverageColumnKind.AGENT) strings.coverageColumnAgents(column.places)
                                    else strings.coverageColumnProjects(column.places),
                                    color = RuleblendTheme.extraColors.faint,
                                    style = MaterialTheme.typography.bodyMedium,
                                ) }
                            } else null,
                            onClick = {
                                val action = targetsFor
                                targetsFor = null
                                if (action != null) onPlan(action, column.id)
                            },
                            modifier = Modifier.testTag(coverageBulkTag("target-${column.id}")),
                        )
                    }
                }
            }
        }
        IconActionButton(
            icon = RuleblendTheme.icons.refresh,
            label = strings.coverageBulkUpdate,
            onClick = { onPlan(CoverageBulkAction.UPDATE, null) },
            enabled = !writing,
            modifier = Modifier.testTag(coverageBulkTag("update")),
        )
        IconActionButton(
            icon = Res.drawable.close,
            label = strings.coverageBulkClear,
            onClick = onClearMarks,
            modifier = Modifier.testTag(coverageBulkTag("clear")),
        )
    }
}

/**
 * The sentence a batch has to be able to say before it runs: how many writes, over how many places,
 * and how many pairs the scope rules left out. A plan with nothing to write is shown too, with its
 * confirmation disabled — "already so" is an answer, and silence would look like a broken button.
 */
@Composable
internal fun BulkPlanDialog(
    plan: CoverageBulkPlan,
    snapshot: CoverageSnapshot,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val target = snapshot.columns.bands(strings).firstNotNullOfOrNull { band ->
        band.columns.find { it.id == plan.target }?.bulkTargetTitle(band)
    }.orEmpty()
    RuleblendDialog(
        onDismiss = onDismiss,
        title = when (plan.action) {
            CoverageBulkAction.INSTALL -> strings.coverageBulkInstallTitle(target)
            CoverageBulkAction.REMOVE -> strings.coverageBulkRemoveTitle(target)
            CoverageBulkAction.UPDATE -> strings.coverageBulkUpdateTitle
        },
        text = if (plan.empty) strings.coverageBulkPlanNothing
        else strings.coverageBulkPlanText(plan.writes, plan.objects, plan.places),
        confirmLabel = strings.coverageBulkApply,
        confirmEnabled = !plan.empty,
        dismissLabel = strings.actionCancel,
        onConfirm = onConfirm,
        modifier = Modifier.testTag(coverageBulkTag("plan")),
    ) {
        if (plan.outOfScope > 0) {
            Text(
                strings.coverageBulkPlanScope(plan.outOfScope),
                style = MaterialTheme.typography.bodyLarge,
                color = RuleblendTheme.extraColors.faint,
            )
        }
    }
}

private fun CoverageColumn.bulkTargetTitle(band: CoverageBand): String =
    if (placeId != null) placeName ?: name ?: placeId.orEmpty() else band.title

/**
 * What the batch did, line by line, the way a single install reports it — plus the places that were
 * asked and took nothing. A partial batch is the normal outcome on a real fleet, so it is stated
 * rather than smoothed over into "done".
 */
@Composable
internal fun BulkReport(report: CoverageBulkReport, strings: Strings, onDismiss: () -> Unit) {
    CoverageBanner(
        text = (listOf(strings.coverageBulkDone) + report.lines(strings)).joinToString("\n"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        onDismiss = onDismiss,
        modifier = Modifier.testTag(coverageBulkTag("report")),
    )
}

/** One line per outcome; a reason that did not occur is left out rather than printed as a zero. */
private fun CoverageBulkReport.lines(strings: Strings): List<String> = buildList {
    add(strings.libInstallWritten(written))
    skipped[SkipReason.OUT_OF_SCOPE]?.let { add(strings.libInstallSkippedScope(it)) }
    skipped[SkipReason.UNSUPPORTED]?.let { add(strings.libInstallSkippedUnsupported(it)) }
    skipped[SkipReason.MODIFIED]?.let { add(strings.libInstallSkippedModified(it)) }
    if (failed > 0) add(strings.libInstallFailedCount(failed))
    if (refused.isNotEmpty()) add(strings.coverageBulkRefused(refused.joinToString(", ")))
}
