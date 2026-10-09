package dev.ruleblend.app.split

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.StatusCard
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.StatusSurface
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.app.theme.DialogCloseButton
import dev.ruleblend.app.theme.workingDialogSize
import dev.ruleblend.core.integration.Section
import dev.ruleblend.core.integration.Splitter
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.model.slugify

/** One part the user built in the dialog: the name they typed and its trimmed body. */
data class SplitPart(val name: String, val body: String)

/** Result payload returned by [SplitDialog] on confirm. Cuts are for callers that also want them. */
data class SplitResult(val cuts: List<Int>, val parts: List<SplitPart>, val description: String)

/**
 * Modal splitter dialog: shows [sourceText] with clickable inter-line gaps on the left, and the
 * resulting parts (with editable names and a body preview) on the right. Two entry points share it —
 * the Library editor (with [existingBlockIds] to guard id collisions) and the adopt dialog (empty
 * set: id collisions there are resolved downstream by `SectionCandidate.initial`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitDialog(
    title: String,
    sourceText: String,
    existingBlockIds: Set<String>,
    nameFormat: NameFormat,
    defaultDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (SplitResult) -> Unit,
) {
    val strings = LocalStrings.current
    val cutsState = remember { mutableStateListOf<Int>() }
    // Sorted view over the cut set; parts depend on this, so a re-sort must trigger recomposition.
    val cuts: List<Int> by remember { derivedStateOf { cutsState.sorted() } }
    val parts: List<Section> by remember(sourceText) {
        derivedStateOf { Splitter.split(sourceText, cuts) }
    }
    // Auto-derived names, regenerated whenever the cut set changes. Changing cuts also changes the
    // part boundaries, so keeping stale user edits would misname the new parts — reset instead.
    val names: SnapshotStateList<String> = remember(cuts) {
        mutableStateListOf<String>().apply {
            addAll(parts.map { formatted(it.displayTitle, nameFormat) })
        }
    }
    var description by remember { mutableStateOf(defaultDescription) }
    val lines = remember(sourceText) { sourceText.split('\n') }
    val canSplit = cuts.isNotEmpty()

    // Re-key on `cuts` so the closures capture the current `names` list — otherwise they would
    // dereference the stale list from the first composition and misreport slug conflicts.
    val partSlugs: List<String> by remember(cuts) {
        derivedStateOf { names.map { slugify(it) } }
    }
    val duplicateSlugs: Set<String> by remember(cuts) {
        derivedStateOf {
            partSlugs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys - ""
        }
    }
    val confirmEnabled: Boolean by remember(cuts) {
        derivedStateOf {
            canSplit &&
                parts.all { it.body.isNotBlank() } &&
                names.all { it.isNotBlank() } &&
                duplicateSlugs.isEmpty() &&
                partSlugs.none { it in existingBlockIds }
        }
    }

    // Two panes side by side, each holding a whole rule: the splitter is the widest surface in the
    // app and used to open as a strip in which every line wrapped.
    val dialogSize = workingDialogSize()
    // The platform default width caps a dialog far below the window; the splitter needs both panes
    // side by side, so it sizes itself off the window instead.
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        // On the box Material wraps the content in, not only on the Surface inside it: that box caps
        // itself at 560dp and would hold the splitter to a strip however wide the Surface asks to be.
        modifier = Modifier.width(dialogSize.width),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 8.dp,
            modifier = Modifier.size(dialogSize),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    DialogCloseButton(strings.actionClose, onDismiss)
                }
                if (lines.size < 2) {
                    Text(
                        strings.splitTooShort,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    ) {
                        CompactOutlinedButton(onClick = onDismiss) { Text(strings.actionCancel) }
                    }
                    return@Column
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SourcePane(
                        lines = lines,
                        cuts = cuts,
                        onToggleCut = { index ->
                            if (index in cutsState) cutsState.remove(index) else cutsState.add(index)
                        },
                        modifier = Modifier.weight(0.55f).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline)
                    PartsPane(
                        parts = parts,
                        names = names,
                        onNameChange = { i, value -> names[i] = value },
                        duplicateSlugs = duplicateSlugs,
                        existingBlockIds = existingBlockIds,
                        onReset = { cutsState.clear() },
                        modifier = Modifier.weight(0.45f).fillMaxHeight(),
                    )
                }
                Column {
                    Text(
                        strings.fieldGeneralDescription.uppercase(),
                        style = SectionHeaderStyle,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    RuleblendOutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        singleLine = true,
                        colors = ruleblendFieldColors(),
                        contentPadding = RuleblendFieldContentPadding,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                ) {
                    CompactOutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("split-cancel")) { Text(strings.actionCancel) }
                    CompactButton(
                        onClick = {
                            val payload = parts.mapIndexed { i, section ->
                                SplitPart(name = names[i], body = section.body)
                            }
                            onConfirm(SplitResult(cuts, payload, description.trim()))
                        },
                        enabled = confirmEnabled,
                        modifier = Modifier.testTag("split-confirm"),
                    ) { Text(strings.splitConfirm(parts.size)) }
                }
            }
        }
    }
}

@Composable
private fun SourcePane(
    lines: List<String>,
    cuts: List<Int>,
    onToggleCut: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val listState = rememberLazyListState()
    // What is being cut up is a rule already in the library, so it is read on the same green mat it
    // wears everywhere else — the parts pane beside it is a form, not the object, and keeps its own
    // plain frames.
    StatusSurface(StatusMark.MANAGED, modifier = modifier) {
        StatusCard(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 4.dp),
                ) {
                    itemsIndexed(lines) { index, line ->
                        if (index > 0) {
                            CutGap(
                                hasCut = index in cuts,
                                onToggle = { onToggleCut(index) },
                                modifier = Modifier.testTag("split-cut-$index"),
                            )
                        }
                        Text(
                            line.ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (cuts.isEmpty()) {
                        item {
                            Text(
                                strings.splitNoCuts,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.padding(horizontal = 4.dp).width(6.dp),
                    style = defaultScrollbarStyle(),
                )
            }
        }
    }
}

@Composable
private fun CutGap(hasCut: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tooltip = if (hasCut) strings.splitRemoveCut else strings.splitAddCut
    RuleblendTooltip(tooltip) {
        Box(
            modifier
                .fillMaxWidth()
                .height(if (hasCut) 14.dp else 8.dp)
                .hoverable(interaction)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            when {
                hasCut -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.primary,
                        thickness = 1.dp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✂",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.primary,
                        thickness = 1.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
                hovered -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.primary,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                else -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun PartsPane(
    parts: List<Section>,
    names: SnapshotStateList<String>,
    onNameChange: (Int, String) -> Unit,
    duplicateSlugs: Set<String>,
    existingBlockIds: Set<String>,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val listState = rememberLazyListState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                strings.splitPartsCount(parts.size),
                style = SectionHeaderStyle,
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedButton(onClick = onReset, enabled = parts.size > 1) {
                Text(strings.splitReset, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(parts) { index, section ->
                    val name = names.getOrElse(index) { "" }
                    val slug = slugify(name)
                    val duplicateHere = slug.isNotEmpty() && slug in duplicateSlugs
                    val conflictsExisting = slug.isNotEmpty() && slug in existingBlockIds
                    PartCard(
                        index = index,
                        section = section,
                        name = name,
                        onNameChange = { onNameChange(index, it) },
                        duplicateHere = duplicateHere,
                        conflictsExisting = conflictsExisting,
                        conflictId = slug,
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.padding(horizontal = 4.dp).width(6.dp),
                style = defaultScrollbarStyle(),
            )
        }
    }
}

@Composable
private fun PartCard(
    index: Int,
    section: Section,
    name: String,
    onNameChange: (String) -> Unit,
    duplicateHere: Boolean,
    conflictsExisting: Boolean,
    conflictId: String,
) {
    val strings = LocalStrings.current
    val emptyBody = section.body.isBlank()
    val hasIssue = emptyBody || duplicateHere || conflictsExisting
    val border = if (hasIssue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = androidx.compose.ui.Modifier.padding(end = 6.dp),
            )
            RuleblendOutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            )
        }
        Box(
            androidx.compose.ui.Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                .padding(6.dp),
        ) {
            Text(
                section.body.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasIssue) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "⚠",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = androidx.compose.ui.Modifier.size(14.dp),
                )
                val message = when {
                    emptyBody -> strings.splitEmptyBody
                    duplicateHere -> strings.splitIdDuplicate
                    else -> strings.splitIdConflict(conflictId)
                }
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Default part name: the section's derived display title, normalized to the configured format. */
private fun formatted(displayTitle: String, format: NameFormat): String =
    formatName(displayTitle.ifBlank { "part" }, format)
