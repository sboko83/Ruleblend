package dev.ruleblend.app.coverage

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import dev.ruleblend.app.theme.RuleblendTooltip

import dev.ruleblend.app.library.KindMark
import dev.ruleblend.app.library.KindMarkSize
import dev.ruleblend.app.library.LibraryObjectKind

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.theme.DisclosureArrow
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.core.integration.InstallStatus

/** A one-place cell's hint: its status as a sentence start, then what a click does there. */
internal fun leafHint(status: String, action: String): String =
    "${status.replaceFirstChar { it.uppercaseChar() }} — ${action.replaceFirstChar { it.lowercaseChar() }}"

internal fun CoverageColumn.cellWidth(): Dp = if (placeId != null) 120.dp else 144.dp

private fun Modifier.leftDivider(enabled: Boolean, color: Color): Modifier =
    if (!enabled) this else drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(color, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
    }

/**
 * The grid. The row head stays put while the cells scroll sideways: one scroll state shared by the
 * header and every row keeps the columns aligned without measuring the whole table.
 */
@Composable
internal fun Matrix(
    snapshot: CoverageSnapshot,
    expansion: CoverageExpansion,
    strings: Strings,
    marked: Set<String>,
    writing: Boolean,
    onToggleRow: (String) -> Unit,
    onToggleColumn: (String) -> Unit,
    onToggleMark: (String) -> Unit,
    onSetInstalled: (LibraryObjectKey, String, Boolean) -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val horizontal = rememberScrollState()
    val listState = rememberLazyListState()
    val bandStarts = snapshot.columns.bands(strings).map { it.columns.first().id }.toSet()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowHead = (maxWidth * 0.28f).coerceIn(240.dp, 340.dp)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(end = 12.dp)) {
                HeaderRow(snapshot, strings, horizontal, rowHead, onToggleColumn, onOpenPlace)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
                    items(snapshot.rows, key = { it.id }) { row ->
                        MatrixRow(
                            row = row,
                            columns = snapshot.columns,
                            expansion = expansion,
                            strings = strings,
                            horizontal = horizontal,
                            rowHead = rowHead,
                            bandStarts = bandStarts,
                            checked = row.id in marked,
                            writing = writing,
                            onToggleRow = onToggleRow,
                            onToggleMark = onToggleMark,
                            onSetInstalled = onSetInstalled,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(listState),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("coverage-scroll-vertical"),
                )
            }
            HorizontalScrollbar(
                rememberScrollbarAdapter(horizontal),
                Modifier.padding(start = rowHead, end = 12.dp).fillMaxWidth().height(12.dp)
                    .testTag("coverage-scroll-horizontal"),
            )
        }
    }
}

@Composable
private fun HeaderRow(
    snapshot: CoverageSnapshot,
    strings: Strings,
    horizontal: ScrollState,
    rowHead: Dp,
    onToggleColumn: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val headingHeight = with(LocalDensity.current) {
        (MaterialTheme.typography.titleMedium.lineHeight.toDp() * 2).coerceAtLeast(44.dp)
    }
    Row(Modifier.fillMaxWidth().height(34.dp + headingHeight)) {
        Box(Modifier.width(rowHead).fillMaxHeight())
        Row(Modifier.leftDivider(true, MaterialTheme.colorScheme.outline).horizontalScroll(horizontal)) {
            snapshot.columns.bands(strings).forEach { band ->
                Column(Modifier.width(band.columns.fold(0.dp) { width, column -> width + column.cellWidth() })
                    .leftDivider(true, MaterialTheme.colorScheme.outline)) {
                    val fullTitle = listOf(band.title, band.suffix).filter { it.isNotEmpty() }.joinToString(" · ")
                    RuleblendTooltip(fullTitle) {
                        Row(
                            Modifier.fillMaxWidth().height(34.dp)
                                .then(if (band.toggleable) Modifier
                                    .clickable { onToggleColumn(band.id) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .testTag(coverageColumnTag(band.id))
                                    .semantics {
                                        contentDescription = if (band.open) strings.coverageCollapseColumn(band.title)
                                        else strings.coverageExpandColumn(band.title)
                                    } else Modifier)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (band.toggleable) DisclosureArrow(open = band.open)
                            Text(
                                buildAnnotatedString {
                                    append(band.title)
                                    if (band.suffix.isNotEmpty()) withStyle(SpanStyle(color = RuleblendTheme.extraColors.faint)) {
                                        append(" · ${band.suffix}")
                                    }
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row {
                        band.columns.forEachIndexed { index, column ->
                            ColumnHeading(column, band.kind, strings, headingHeight, index == 0, onOpenPlace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnHeading(
    column: CoverageColumn,
    bandKind: CoverageColumnKind,
    strings: Strings,
    height: Dp,
    bandStart: Boolean,
    onOpenPlace: (String) -> Unit,
) {
    val placeId = column.placeId
    val name = column.placeName ?: column.name ?: placeId.orEmpty()
    val interactions = remember(column.id) { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val label = if (placeId != null) name else when (bandKind) {
        CoverageColumnKind.AGENT -> strings.coverageColumnAgents(column.places)
        else -> strings.coverageColumnProjects(column.places)
    }
    Box(Modifier.width(column.cellWidth()).height(height).leftDivider(bandStart, MaterialTheme.colorScheme.outline), contentAlignment = Alignment.Center) {
        if (placeId != null) {
            RuleblendTooltip(strings.coverageOpenPlaceNamed(name)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    textDecoration = if (hovered) TextDecoration.Underline else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .clickable(interactionSource = interactions, indication = null) { onOpenPlace(placeId) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { contentDescription = strings.coverageOpenPlaceNamed(name) }
                        .testTag(coveragePlaceTag(column.id))
                        .padding(horizontal = 6.dp),
                )
            }
        } else {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = RuleblendTheme.extraColors.faint, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun MatrixRow(
    row: CoverageRow,
    columns: List<CoverageColumn>,
    expansion: CoverageExpansion,
    strings: Strings,
    horizontal: ScrollState,
    rowHead: Dp,
    bandStarts: Set<String>,
    checked: Boolean,
    writing: Boolean,
    onToggleRow: (String) -> Unit,
    onToggleMark: (String) -> Unit,
    onSetInstalled: (LibraryObjectKey, String, Boolean) -> Unit,
) {
    val summary = row.kind == CoverageRowKind.ALL || row.kind == CoverageRowKind.TYPE
    Row(
        Modifier.fillMaxWidth().height(if (row.kind == CoverageRowKind.OBJECT) 40.dp else 52.dp)
            .background(if (summary) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(rowHead)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The one row that cannot be marked keeps the width anyway: titles that shift by a box
            // when the row above them is markable read as two different columns.
            if (row.markable) {
                RuleblendTooltip(strings.coverageMarkRow) {
                    SquareCheckbox(
                        checked = checked,
                        onCheckedChange = { onToggleMark(row.id) },
                        modifier = Modifier.testTag(coverageMarkTag(row.id))
                            .semantics { contentDescription = strings.coverageMarkRow },
                    )
                }
            } else {
                Box(Modifier.width(16.dp))
            }
            Box(Modifier.width(6.dp))
            Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                if (row.expandable) {
                    val open = row.id in expansion.rows
                    Chevron(
                        open = open,
                        description = if (open) strings.coverageCollapseRow(row.title(strings))
                        else strings.coverageExpandRow(row.title(strings)),
                        onClick = { onToggleRow(row.id) },
                        modifier = Modifier.testTag(coverageRowTag(row.id)),
                    )
                }
            }
            if (row.kind == CoverageRowKind.OBJECT) Box(Modifier.width(18.dp))
            val objectKind = row.key?.kind ?: row.objectKind
                ?: LibraryObjectKind.GROUP.takeIf { row.kind == CoverageRowKind.GROUP }
            Box(Modifier.width(KindMarkSize)) { objectKind?.let { KindMark(it) } }
            Box(Modifier.width(6.dp))
            Text(
                row.title(strings),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (summary) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("coverage-title-${row.id}"),
            )
            if (row.kind != CoverageRowKind.OBJECT) {
                RuleblendTooltip(strings.coverageRowCount(row.total)) {
                    Text(row.total.toString(), style = MaterialTheme.typography.bodyLarge,
                        color = RuleblendTheme.extraColors.faint, modifier = Modifier.padding(start = 6.dp))
                }
            } else if (!row.version.isNullOrBlank()) {
                Text("@${row.version}", style = MaterialTheme.typography.bodyLarge,
                    color = RuleblendTheme.extraColors.faint, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(Modifier.leftDivider(true, MaterialTheme.colorScheme.outline).horizontalScroll(horizontal), verticalAlignment = Alignment.CenterVertically) {
            row.cells.forEachIndexed { index, cell ->
                val column = columns.getOrNull(index)
                val key = row.key
                val placeId = column?.placeId
                val write = column != null && key != null && placeId != null && row.writable(column, cell)
                CellView(
                    cell = cell,
                    leaf = write,
                    strings = strings,
                    width = column?.cellWidth() ?: 144.dp,
                    bandStart = column?.id in bandStarts,
                    objectRow = row.kind == CoverageRowKind.OBJECT,
                    writing = writing,
                    modifier = Modifier.testTag(coverageCellTag(row.id, column?.id.orEmpty())),
                    onClick = if (write) {
                        { onSetInstalled(key, placeId, cell.installed == 0) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * One cell. An aggregate reads as `installed/members` above the mix bar; an opened object in one
 * place reads as a mark that can be clicked — a dot to take it out, a plus to put it there. A dash is
 * neither, because there is nothing to count and nothing a click could do.
 */
@Composable
private fun CellView(
    cell: CoverageCell,
    leaf: Boolean,
    strings: Strings,
    width: Dp,
    bandStart: Boolean,
    objectRow: Boolean,
    writing: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    val extras = RuleblendTheme.extraColors
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val status = when (cell.status) {
        InstallStatus.MODIFIED -> strings.coverageLegendModified
        InstallStatus.UPDATE_AVAILABLE -> strings.coverageLegendUpdate
        else -> strings.coverageLegendSynced
    }
    val tooltip = when {
        cell.outOfScope -> strings.coverageLegendOutOfScope
        leaf && cell.installed > 0 -> leafHint(status, strings.coverageRemoveHere)
        leaf -> leafHint(strings.coverageLegendMissing, strings.coverageInstallHere)
        else -> buildString {
            append(if (objectRow) strings.coverageCellPlaces(cell.installed, cell.members)
                else strings.coverageCellObjects(cell.installed, cell.members))
            if (cell.installed > 0) append("\n${strings.coverageCellSplit(cell.synced, cell.updates, cell.modified)}")
        }
    }
    RuleblendTooltip(tooltip) {
        Column(
            modifier.width(width).fillMaxHeight()
                .then(if (onClick != null) Modifier
                    .hoverable(interactions)
                    .background(if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .pointerHoverIcon(if (writing) PointerIcon.Default else PointerIcon.Hand)
                    .clickable(enabled = !writing, interactionSource = interactions, indication = null, onClick = onClick)
                    else Modifier)
                .leftDivider(bandStart, MaterialTheme.colorScheme.outline)
                .semantics { contentDescription = tooltip }
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            if (cell.outOfScope) {
                Text("—", style = MaterialTheme.typography.bodyLarge, color = extras.faint)
                return@Column
            }
            if (leaf) {
                if (cell.installed > 0) {
                    StatusDot(
                        when (cell.status) {
                            InstallStatus.MODIFIED -> extras.badgeModFg
                            InstallStatus.UPDATE_AVAILABLE -> extras.badgeUpdFg
                            else -> extras.badgeOkFg
                        },
                    )
                } else {
                    Text(
                        "+",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hovered) MaterialTheme.colorScheme.primary else extras.faint,
                    )
                }
                return@Column
            }
            Text(
                "${cell.installed}/${cell.members}",
                style = MaterialTheme.typography.bodyLarge,
                color = when (cell.status) {
                    InstallStatus.MODIFIED -> extras.badgeModFg
                    InstallStatus.UPDATE_AVAILABLE -> extras.badgeUpdFg
                    InstallStatus.SYNCED -> MaterialTheme.colorScheme.onSurface
                    null -> extras.faint
                },
            )
            MiniBar(cell, Modifier.fillMaxWidth().testTag("coverage-cell-track"))
        }
    }
}

/** The one open/closed mark of the surface, named so a click has something to say out loud. */
@Composable
private fun Chevron(
    open: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisclosureArrow(
        open = open,
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 2.dp),
    )
}

/** The installed mix over a neutral track; the track is what is still missing, not a fourth status. */
@Composable
internal fun MiniBar(cell: CoverageCell, modifier: Modifier = Modifier) {
    val extras = RuleblendTheme.extraColors
    Row(
        modifier.height(7.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(3.dp)),
    ) {
        listOf(
            cell.synced to extras.badgeOkFg,
            cell.updates to extras.badgeUpdFg,
            cell.modified to extras.badgeModFg,
            cell.missing to Color.Transparent,
        ).filter { it.first > 0 }.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
        }
    }
}
