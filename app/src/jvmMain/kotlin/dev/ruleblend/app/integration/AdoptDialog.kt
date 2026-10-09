package dev.ruleblend.app.integration

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.library.ContentWithTranslation
import dev.ruleblend.app.library.TranslationControls
import dev.ruleblend.app.library.ScopePicker
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.split.SplitDialog
import dev.ruleblend.app.theme.workingDialogSize
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.RuleblendTooltipRich
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.integration.AdoptPlacement
import dev.ruleblend.core.integration.AdoptScanResult
import dev.ruleblend.core.integration.MatchKind
import dev.ruleblend.core.integration.Section
import dev.ruleblend.core.integration.SectionAction
import dev.ruleblend.core.integration.SectionCandidate
import dev.ruleblend.core.integration.SectionExtractor
import dev.ruleblend.core.integration.Splitter
import dev.ruleblend.core.integration.adoptSizeWarning
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.model.slugify

/**
 * Turning the hand-written text of one file into library blocks: one block for a single-section file,
 * a per-section plan for a file with several. Shared by the Place palette's *Found in project* rows
 * and the legacy checklist — the adopt contract must not fork per surface.
 */
/** How much of the dialog a translated body may take before it scrolls instead of growing. */
private val AdoptTranslationHeight = 280.dp
private val AdoptFormMinWidth = 340.dp
private val AdoptFormMaxWidth = 460.dp

@Composable
internal fun AdoptDialog(
    file: UnmanagedFile,
    targetName: String,
    libraryBlocks: List<Block>,
    nameFormat: NameFormat,
    scan: (String) -> AdoptScanResult,
    /** `null` where translation is not available, which also keeps UI tests off the system helper. */
    translation: TranslationModel? = null,
    /** Project keys a saved rule can be pinned to; the picker also offers the global scope. */
    projectScopes: List<String> = emptyList(),
    /** Scope the picker opens on: the project this file was found in, or `null` for global. */
    initialScope: String? = null,
    onDismiss: () -> Unit,
    onAdopt: (name: String, description: String, replace: Boolean, scope: String?) -> Unit,
    onBatchAdopt: (
        candidates: List<SectionCandidate>,
        description: String,
        replace: Boolean,
        scope: String?,
    ) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(formatName(file.name.substringBeforeLast('.'), nameFormat)) }
    // Pre-fill a sensible default: source target and file, so the user rarely types from scratch.
    var description by remember { mutableStateOf(strings.intAdoptDescriptionDefault(targetName, file.name)) }
    var replace by remember { mutableStateOf(false) }
    // One scope for the whole save: a file split into several sections is still one file, found in
    // one project, and picking a project per section would be a decision nobody wants to make twice.
    var scope by remember(file) { mutableStateOf(initialScope) }
    val oversize = remember(file) { adoptSizeWarning(file.content.text) }
    // A translation belongs to the text it was made from; another file is another text.
    LaunchedEffect(file) { translation?.reset() }

    // Sections + dedup matches, computed once per file. Mutable only via the checkboxes/menu below.
    // Non-offerable sections (blank bodies, comment banners) ride along as fixed SKIP rows so the
    // write-back plan covers every source line; only offerable ones are shown and counted.
    var candidates by remember(file) {
        mutableStateOf(
            SectionExtractor.extract(file.content.text)
                .map { SectionCandidate.initial(it, libraryBlocks) },
        )
    }
    // Index into [candidates] of the section the user is splitting further; null when the splitter
    // is closed. Kept here so a Split cascades back into the section list this dialog owns.
    var splittingIndex by remember(file) { mutableStateOf<Int?>(null) }
    val multiSection = candidates.count { it.section.offerable } >= 2
    // Advisory pre-save scan. Whole-file view in single-section mode, per-section view otherwise —
    // the WARN badge on a section row tells the user which one carries the finding.
    val wholeScan = remember(file) { scan(file.content.text) }
    // Cache scans by section identity so a checkbox flip doesn't rescan every section, and a
    // ✂ split still gets fresh scans for the new sub-sections.
    val scanCache = remember(file) { mutableMapOf<Section, AdoptScanResult>() }
    val sectionScans: Map<Section, AdoptScanResult> by remember(file) {
        derivedStateOf {
            candidates.forEach { c ->
                scanCache.getOrPut(c.section) {
                    if (c.section.offerable) scan(c.section.body)
                    else AdoptScanResult(emptyList(), emptyList())
                }
            }
            scanCache.toMap()
        }
    }

    // The file keeps one managed run: a replacement that would move hand-written text is refused here,
    // before anything reaches the library, rather than half-done after the save.
    val orderBlockers = when {
        !replace -> emptyList()
        multiSection -> AdoptPlacement.blockers(
            candidates.map { it.section.sourceText },
            candidates.map { it.included && it.action != SectionAction.SKIP },
            file.content.linesBeforeRun,
        )
        else -> AdoptPlacement.blockers(listOf(file.content.text), listOf(true), file.content.linesBeforeRun)
    }
    val orderMessage = orderBlockers.takeIf { it.isNotEmpty() }?.let { blockers ->
        val split = !multiSection || blockers.any { candidates[it].included && candidates[it].action != SectionAction.SKIP }
        if (split) strings.intAdoptOrderAroundRun
        else strings.intAdoptOrderBlocked(blockers.joinToString(", ") { "«${candidates[it].section.displayTitle}»" })
    }

    // A list of sections to pick from, or a text against its translation, is a working surface and
    // not a question: at the dialog's default width both of them turn into columns of broken lines.
    // A plain one-section adopt stays a form — it is two fields and a checkbox.
    val working = multiSection || translation?.visible == true
    val workingWidth = workingDialogSize().width
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.intAdoptTitle(file.name),
        confirmLabel = strings.actionAdopt,
        confirmEnabled = orderMessage == null && if (multiSection) candidates.any { it.included } else name.isNotBlank(),
        onConfirm = {
            if (multiSection) onBatchAdopt(candidates, description.trim(), replace, scope)
            else onAdopt(name.trim(), description.trim(), replace, scope)
            onDismiss()
        },
        dismissLabel = strings.actionCancel,
        minWidth = if (working) workingWidth else AdoptFormMinWidth,
        maxWidth = if (working) workingWidth else AdoptFormMaxWidth,
        usePlatformDefaultWidth = !working,
    ) {
        if (!multiSection) {
            LabelledField(strings.fieldName) {
                RuleblendOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.fillMaxWidth().testTag("adopt-name"),
                )
            }
        }
        LabelledField(strings.fieldGeneralDescription) {
            RuleblendOutlinedTextField(
                value = description,
                onValueChange = { description = it },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().testTag("adopt-description"),
            )
        }
        ScopePicker(
            scope = scope,
            scopes = projectScopes,
            onSelect = { scope = it },
            resetKey = file,
        )
        // What is about to be saved, in the reader's language. The dialog keeps its shape until the
        // panel is opened: a person who writes in the file's language never sees a second column.
        translation?.let {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TranslationControls(
                    model = it,
                    content = file.content.text,
                    canReplace = false,
                    onReplace = {},
                )
            }
            if (it.visible) {
                Box(Modifier.fillMaxWidth().heightIn(max = AdoptTranslationHeight)) {
                    // Grey: this text is still nobody's, which is the whole reason the dialog is
                    // open. It turns green on the Places screen the moment it is adopted.
                    ContentWithTranslation(it, file.content.text, StatusMark.UNMANAGED) { paneModifier ->
                        Text(
                            file.content.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = paneModifier,
                        )
                    }
                }
            }
        }
        if (multiSection) SectionsPanel(
            candidates = candidates,
            scans = sectionScans,
            nameFormat = nameFormat,
            onChange = { index, updated ->
                candidates = candidates.toMutableList().also { it[index] = updated }
            },
            onSplit = { index -> splittingIndex = index },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SquareCheckbox(checked = replace, onCheckedChange = { replace = it }, modifier = Modifier.testTag("adopt-replace"))
            Text(strings.intAdoptReplaceLabel, style = MaterialTheme.typography.bodySmall)
        }
        orderMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("adopt-order-blocked"),
            )
        }
        // Advisory only: a big block is still the user's call, so the confirm button stays enabled.
        oversize?.let {
            Text(
                strings.intAdoptSizeWarning(it.lines, it.bytes / 1024),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // In single-section mode there is no row to hang the WARN badge on — show a summary line
        // with a tooltip carrying the full list. In multi mode the badges on each row cover it.
        if (!multiSection && !wholeScan.isEmpty) AdoptScanSummary(wholeScan)
    }

    splittingIndex?.let { idx ->
        val source = candidates.getOrNull(idx)?.section ?: run { splittingIndex = null; return@let }
        SplitDialog(
            title = strings.splitDialogTitleAdopt,
            sourceText = source.body,
            existingBlockIds = emptySet(),
            nameFormat = nameFormat,
            defaultDescription = description,
            onDismiss = { splittingIndex = null },
            onConfirm = { result ->
                val subs = Splitter.splitSection(source, result.cuts)
                val subCandidates = subs.mapIndexed { i, section ->
                    val editedName = result.parts.getOrNull(i)?.name.orEmpty()
                    val named = if (editedName.isNotBlank())
                        section.copy(title = editedName, slug = slugify(editedName))
                    else section
                    SectionCandidate.initial(named, libraryBlocks)
                }
                candidates = candidates.toMutableList().apply {
                    removeAt(idx)
                    addAll(idx, subCandidates)
                }
                splittingIndex = null
            },
        )
    }
}

@Composable
private fun AdoptScanSummary(scan: AdoptScanResult) {
    val strings = LocalStrings.current
    RuleblendTooltipRich(tooltip = { ScanTooltipBody(scan) }) {
        Text(
            "⚠ " + strings.intAdoptScanSummary(scan.imports.size, scan.brokenLinks.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanTooltipBody(scan: AdoptScanResult) {
    val strings = LocalStrings.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        if (scan.imports.isNotEmpty()) {
            Text(strings.intAdoptScanImportsHeader(scan.imports.size), style = MaterialTheme.typography.labelMedium)
            scan.imports.forEach { Text("@$it", style = MaterialTheme.typography.bodySmall, fontFamily = MonoFontFamily) }
            Text(strings.intAdoptScanImportsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (scan.brokenLinks.isNotEmpty()) {
            Text(strings.intAdoptScanBrokenHeader(scan.brokenLinks.size), style = MaterialTheme.typography.labelMedium)
            scan.brokenLinks.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFontFamily) }
            Text(strings.intAdoptScanBrokenHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionsPanel(
    candidates: List<SectionCandidate>,
    scans: Map<Section, AdoptScanResult>,
    nameFormat: NameFormat,
    onChange: (Int, SectionCandidate) -> Unit,
    onSplit: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    // Non-offerable candidates stay in the list (write-back needs them) but get no row and never
    // toggle: they are permanent SKIPs.
    val visible = candidates.withIndex().filter { it.value.section.offerable }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.intAdoptSections(visible.size), style = SectionHeaderStyle, modifier = Modifier.weight(1f))
            CompactGhostButton(onClick = { visible.forEach { (i, c) -> onChange(i, c.copy(included = true)) } }) { Text(strings.intAdoptSelectAll) }
            CompactGhostButton(onClick = { visible.forEach { (i, c) -> onChange(i, c.copy(included = false)) } }) { Text(strings.intAdoptSelectNone) }
        }
        // Scroll the rows, never the whole dialog: the Save/Cancel buttons must stay on screen. The
        // visible scrollbar signals that the box scrolls — the list alone does not make that obvious.
        val listState = rememberLazyListState()
        Row(Modifier.heightIn(max = 240.dp).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(visible, key = { (index, c) -> index to c.section.slug }) { (index, candidate) ->
                    SectionRow(
                        candidate = candidate,
                        scan = scans[candidate.section],
                        nameFormat = nameFormat,
                        onUpdated = { updated -> onChange(index, updated) },
                        onSplit = { onSplit(index) },
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.padding(start = 6.dp).width(6.dp),
                style = defaultScrollbarStyle(),
            )
        }
    }
}

@Composable
private fun SectionRow(
    candidate: SectionCandidate,
    scan: AdoptScanResult?,
    nameFormat: NameFormat,
    onUpdated: (SectionCandidate) -> Unit,
    onSplit: () -> Unit,
) {
    val strings = LocalStrings.current
    val extras = RuleblendTheme.extraColors
    val accent = when (candidate.match.kind) {
        MatchKind.NEW -> extras.badgeOkFg
        MatchKind.DUPLICATE -> MaterialTheme.colorScheme.onSurfaceVariant
        MatchKind.SIMILAR -> extras.badgeUpdFg
    }
    // A 100% duplicate has nothing to import: the library already holds this exact text. It collapses
    // to a status label, and its checkbox is off and disabled so the row cannot create a copy.
    val duplicate = candidate.match.kind == MatchKind.DUPLICATE
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        SquareCheckbox(
            checked = candidate.included && !duplicate,
            enabled = !duplicate,
            modifier = Modifier.testTag("adopt-section:${candidate.section.slug}"),
            onCheckedChange = { checked ->
                onUpdated(
                    candidate.copy(
                        included = checked,
                        // Only matched sections (duplicate/similar) toggle between Skip and Overwrite
                        // with the checkbox. NEW sections keep their Create-new action either way —
                        // forcing SKIP here used to drop ticked new sections on save.
                        action = when {
                            candidate.match.target == null -> candidate.action
                            checked -> SectionAction.OVERWRITE
                            else -> SectionAction.SKIP
                        },
                    ),
                )
            },
        )
        Text(
            savedName(candidate.section, nameFormat),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).testTag("adopt-section-name:${candidate.section.slug}"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // ⓘ shows exactly what will be saved as the block body, so the name + content can be checked
        // before import without opening the file.
        SectionInfoButton(candidate.section)
        // ⚠ appears only when the section has @imports or unresolved local links — the tooltip lists
        // them. Same visual weight as ⓘ; no action, just awareness.
        if (scan != null && !scan.isEmpty) SectionWarnBadge(scan)
        // ✂ cuts this section further into sub-blocks. Hidden for duplicates: a section already in
        // the library has nothing to gain from a further split at this point.
        if (!duplicate && candidate.section.body.contains('\n')) SectionSplitButton(onSplit)
        Text(
            sectionStatusText(candidate, strings),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.testTag("adopt-section-status:${candidate.section.slug}"),
        )
        if (!duplicate && candidate.match.target != null) ActionMenu(candidate, onUpdated)
    }
}

/** ✂ — opens the splitter dialog on this section. Same visual weight as the ⓘ / ⚠ glyphs beside it. */
@Composable
private fun SectionSplitButton(onSplit: () -> Unit) {
    val strings = LocalStrings.current
    RuleblendTooltip(strings.splitAction) {
        Text(
            "✂",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .wrapContentSize(Alignment.Center)
                .clickable(onClick = onSplit),
        )
    }
}

/**
 * ⚠ that, on hover, shows the section's `@imports` and unresolved local links — the same list the
 * whole-file summary shows in single-section mode. No action, awareness only: the user can still
 * save.
 */
@Composable
private fun SectionWarnBadge(scan: AdoptScanResult) {
    RuleblendTooltipRich(tooltip = { ScanTooltipBody(scan) }) {
        Text(
            "⚠",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).wrapContentSize(Alignment.Center),
        )
    }
}

/**
 * A small ⓘ that, on hover, shows the section body in a styled popup — the exact text that will be
 * saved as the block content. Cheap to scan: a one-line row name plus the full body on demand.
 */
@Composable
private fun SectionInfoButton(section: Section) {
    RuleblendTooltipRich(
        tooltip = {
            // verticalScroll lets long bodies render in full without growing the popup unbounded.
            Box(
                Modifier.verticalScroll(rememberScrollState()).widthIn(max = 444.dp).heightIn(max = 308.dp),
            ) {
                Text(
                    section.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) {
        Text(
            "ⓘ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).wrapContentSize(Alignment.Center),
        )
    }
}

@Composable
private fun ActionMenu(candidate: SectionCandidate, onUpdated: (SectionCandidate) -> Unit) {
    val strings = LocalStrings.current
    val current = when (candidate.action) {
        SectionAction.SKIP -> strings.intAdoptActionSkip
        SectionAction.CREATE_NEW -> strings.intAdoptActionCreateNew
        SectionAction.OVERWRITE -> strings.intAdoptActionOverwrite
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        CompactOutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("adopt-section-action:${candidate.section.slug}"),
        ) { Text(current) }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownItem(strings.intAdoptActionSkip) { onUpdated(candidate.copy(action = SectionAction.SKIP)); expanded = false }
            DropdownItem(strings.intAdoptActionOverwrite) { onUpdated(candidate.copy(action = SectionAction.OVERWRITE)); expanded = false }
            DropdownItem(strings.intAdoptActionCreateNew) { onUpdated(candidate.copy(action = SectionAction.CREATE_NEW)); expanded = false }
        }
    }
}

@Composable
private fun DropdownItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
    )
}

private fun sectionStatusText(candidate: SectionCandidate, strings: Strings): String = when (candidate.match.kind) {
    MatchKind.NEW -> strings.intAdoptSectionNew
    MatchKind.DUPLICATE -> strings.intAdoptSectionDuplicate
    MatchKind.SIMILAR -> strings.intAdoptSectionSimilar(candidate.match.target?.name.orEmpty())
}

/** Label above the box, matching the block editor's field style. */
@Composable
private fun LabelledField(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = SectionHeaderStyle)
        field()
    }
}
