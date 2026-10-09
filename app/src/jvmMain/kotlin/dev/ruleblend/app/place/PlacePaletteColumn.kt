package dev.ruleblend.app.place

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.integration.AdoptDialog
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.integration.UnmanagedFile
import dev.ruleblend.app.library.FavoriteMark
import dev.ruleblend.app.library.KindMark
import dev.ruleblend.app.library.KindMarkSize
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactPillButton
import dev.ruleblend.app.theme.DisclosureArrow
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.FieldClearButton
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.StatusBadge
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.SyncBadge
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.ProjectTarget
import java.nio.file.Path
import kotlinx.coroutines.launch

/** How many rows of one type the palette shows before it asks to be told to show more. */
private const val PAGE = 30

/**
 * Right column of the Place surface: what the library can offer this place. The hand-written files
 * found here first, then what the project's set recommends, then the searchable catalog by type —
 * along with the one move that goes the other way, saving this place into the library as a group.
 *
 * Rows both report and write: install, update and remove run from here through the model's existing
 * services. Resolving a hand edit belongs to the file preview, where the edit is visible.
 *
 * A *Found in project* row adopts the file's hand-written text into the library through the same
 * [AdoptDialog] the checklist uses, or hands the file to the editor via [onEditFile] — for a project
 * that has never been managed, this is the first thing to do here, not an appendix to another tab.
 */
@Composable
fun PlacePaletteColumn(
    model: IntegrationModel,
    onEditFile: (Path) -> Unit,
    libraryExpandedByDefault: Boolean = true,
    /** `null` where translation is not available, which also keeps UI tests off the system helper. */
    translation: TranslationModel? = null,
    selectedTab: PlaceTabKind? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    // Installing, adopting and saving a group all write to disk: off the frame that handled the click.
    val scope = rememberCoroutineScope()
    val target = model.selected ?: return
    var query by remember(target) { mutableStateOf("") }
    var collapsed by remember(target, libraryExpandedByDefault, selectedTab) {
        val activeKind = when (selectedTab) {
            PlaceTabKind.RULES -> LibraryObjectKind.RULE
            PlaceTabKind.SKILLS -> LibraryObjectKind.SKILL
            PlaceTabKind.SUBAGENTS -> LibraryObjectKind.SUBAGENT
            PlaceTabKind.MCP -> LibraryObjectKind.MCP
            null -> null
        }
        mutableStateOf(
            if (activeKind != null) LibraryObjectKind.entries.toSet() - activeKind
            else if (libraryExpandedByDefault) emptySet() else LibraryObjectKind.entries.toSet(),
        )
    }
    var shown by remember(target) { mutableStateOf(emptyMap<LibraryObjectKind, Int>()) }
    var adopting by remember(target) { mutableStateOf<UnmanagedFile?>(null) }
    var savingGroup by remember(target) { mutableStateOf(false) }

    val status = model.paletteStatus()
    val installedHere = placeMembers(model.catalog, status)
    val sections = paletteSections(model.catalog, query, status)
    val recommendations = paletteRecommendations(
        catalog = model.catalog,
        board = model.board,
        target = target,
        usage = model.usage,
        status = status,
    )
    val files = model.unmanagedFiles + model.referencedFiles
    val found = paletteFoundFiles(files)
    // Keyed by place: the column is the same composable for every place, and carrying one place's
    // scroll offset into the next hides the section that names the new place at the top.
    val listState = remember(target) { LazyListState() }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Padding inside the list rather than around it: a header's rule reaches the column's edges.
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).testTag("place-palette-list"),
            ) {
                // The hand-written files of this place come first: for a project that has never been
                // managed they are the only thing here to act on, and they name the place itself.
                if (found.isNotEmpty()) {
                    item("found") {
                        PaletteHeader(
                            if (target is ProjectTarget) strings.paletteFound else strings.paletteFoundAgent,
                        )
                    }
                    items(found, key = { "found:${it.name}" }) { file ->
                        FoundRow(
                            file = file,
                            strings = strings,
                            // The row carries a path; the dialog needs the file the model already read,
                            // so the text it adopts is the same text the row counted.
                            onAdopt = { adopting = files.find { it.path == file.path } },
                            onEdit = { onEditFile(file.path) },
                        )
                    }
                }
                if (recommendations.isNotEmpty()) {
                    item("recommended") {
                        PaletteHeader(strings.paletteRecommended, first = found.isEmpty())
                    }
                    items(recommendations, key = { "rec:${it.key.kind}:${it.key.id}" }) { row ->
                        RecommendationRow(row, strings, model)
                    }
                }
                item("library") {
                    PaletteHeader(
                        strings.paletteLibrary,
                        first = found.isEmpty() && recommendations.isEmpty(),
                        actions = {
                            HeaderGlyphButton("+", strings.paletteExpandAll) { collapsed = emptySet() }
                            HeaderGlyphButton("−", strings.paletteCollapseAll) {
                                collapsed = sections.map { it.kind }.toSet()
                            }
                        },
                    )
                }
                item("save-as-group") {
                    // Nothing installed here yet: a captured group would be a bundle that does nothing.
                    CompactPillButton(
                        onClick = { savingGroup = true },
                        enabled = !installedHere.isEmpty,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("place-save-as-group"),
                    ) {
                        Text(strings.paletteSaveAsGroup, style = MaterialTheme.typography.labelMedium)
                    }
                }
                item("search") {
                    RuleblendOutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(strings.paletteSearch(model.catalog.objects.size)) },
                        singleLine = true,
                        colors = ruleblendFieldColors(),
                        contentPadding = RuleblendFieldContentPadding,
                        // Same reset the place filter carries: a search is typed far more often than
                        // it is cleared character by character.
                        trailingIcon = if (query.isEmpty()) null else {
                            { FieldClearButton(strings.paletteSearchClear) { query = "" } }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("place-palette-search"),
                    )
                }
                sections.forEach { section ->
                    // A query opens what it matched: a hit hidden under a collapsed header is not a hit.
                    val open = section.kind !in collapsed || query.isNotBlank() && section.rows.isNotEmpty()
                    item(key = "type:${section.kind}") {
                        TypeHeader(
                            title = section.kind.title(strings),
                            total = section.total,
                            open = open,
                            onToggle = {
                                collapsed =
                                    if (section.kind in collapsed) collapsed - section.kind else collapsed + section.kind
                            },
                        )
                    }
                    if (!open) return@forEach
                    val limit = shown[section.kind] ?: PAGE
                    items(section.rows.take(limit), key = { "obj:${it.key.kind}:${it.key.id}" }) { row ->
                        ObjectRow(row, strings, model)
                    }
                    val left = section.rows.size - limit
                    if (left > 0) {
                        item("more:${section.kind}") {
                            MoreRow(strings.paletteShowMore(left)) {
                                shown = shown + (section.kind to limit + PAGE)
                            }
                        }
                    }
                }
                if (sections.all { it.rows.isEmpty() }) {
                    item("empty") { Note(strings.paletteNoMatches) }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.padding(vertical = 2.dp),
                style = defaultScrollbarStyle(),
            )
        }
    }

    if (savingGroup) {
        GroupNameDialog(
            count = installedHere.size,
            onDismiss = { savingGroup = false },
            onConfirm = { name ->
                scope.launch { model.saveAsGroup(name, installedHere) }
                savingGroup = false
            },
        )
    }

    adopting?.let { file ->
        // Read once: `nameFormat` parses the config file, and the dialog recomposes on every keystroke.
        val nameFormat = remember(file) { model.nameFormat }
        AdoptDialog(
            file = file,
            targetName = target.name,
            libraryBlocks = model.allLibraryBlocks.filter { it.type == dev.ruleblend.core.model.BlockType.RULE },
            nameFormat = nameFormat,
            scan = { text -> model.scanAdopt(file, text) },
            translation = translation,
            projectScopes = model.projectScopes,
            initialScope = model.selectedProjectScope(),
            onDismiss = { adopting = null },
            onAdopt = { name, description, replace, adoptScope ->
                scope.launch { model.adopt(file, name, description, replace, adoptScope) }
            },
            onBatchAdopt = { candidates, description, replace, adoptScope ->
                scope.launch { model.batchAdopt(file, candidates, description, replace, adoptScope) }
            },
        )
    }
}

private fun LibraryObjectKind.title(strings: Strings): String = when (this) {
    LibraryObjectKind.RULE -> strings.libRulesLabel
    LibraryObjectKind.SUBAGENT -> strings.libSubagents
    LibraryObjectKind.SKILL -> strings.libSkills
    LibraryObjectKind.MCP -> strings.libMcp
    LibraryObjectKind.GROUP -> strings.libGroups
    LibraryObjectKind.PROFILE -> strings.libProfiles
}

/**
 * A block header of this column, one step above the type headers inside it: the blocks are separate
 * lists, not one long one, so each is titled over a rule that runs the column's full width. [hint]
 * explains the block on hover — for a name that is a term of this app rather than of the file
 * system, that explanation has nowhere else to live. [actions] sit at the end of the same line, so
 * they read as belonging to the block they act on.
 */
@Composable
private fun PaletteHeader(
    title: String,
    hint: String? = null,
    /** The column's top block: nothing above it to keep a distance from. */
    first: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 16.dp, bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 4.dp),
        ) {
            val label: @Composable () -> Unit = {
                Text(title.uppercase(), style = PaletteHeaderStyle, maxLines = 1)
            }
            if (hint == null) label() else RuleblendTooltip(hint) { label() }
            Spacer(Modifier.weight(1f))
            actions?.invoke(this)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.bleed(8.dp))
    }
}

private val PaletteHeaderStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.8.sp,
    )

/** Stretches the element [horizontal] past both sides of its slot, over the padding of the list. */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx()
    val width = constraints.maxWidth + 2 * extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** A lone-glyph header button: the header line is tight, so the label is a sign and the words a tooltip. */
@Composable
private fun HeaderGlyphButton(glyph: String, hint: String, onClick: () -> Unit) {
    RuleblendTooltip(hint) {
        CompactGhostButton(onClick = onClick) {
            Text(glyph, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Light))
        }
    }
}

@Composable
private fun TypeHeader(title: String, total: Int, open: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        DisclosureArrow(open, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title.uppercase(), style = SectionHeaderStyle, modifier = Modifier.weight(1f), maxLines = 1)
        Text(total.toString(), style = SectionHeaderStyle)
    }
}

@Composable
private fun ObjectRow(row: PaletteRow, strings: Strings, model: IntegrationModel) {
    val scope = rememberCoroutineScope()
    val supported = model.placeSupports(row.key)
    val actions = paletteActions(
        kind = row.key.kind,
        status = row.status,
        installable = supported,
        groupComplete = row.key.kind == LibraryObjectKind.GROUP && model.groupComplete(row.key.id),
    )
    PaletteCard(
        namePrefixWidth = KindMarkSize + 4.dp,
        name = {
            KindMark(row.key.kind)
            Spacer(Modifier.width(4.dp))
            // The star only reports: marking a favorite is a library decision, made in the library.
            if (row.favorite) {
                FavoriteMark()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            StatusPill(row.status, strings)
            if (!supported) {
                Text(
                    strings.paletteUnsupported,
                    style = MaterialTheme.typography.labelSmall,
                    color = RuleblendTheme.extraColors.faint,
                    maxLines = 1,
                )
            }
            actions.forEach { action ->
                PaletteActionButton(
                    action, strings,
                    modifier = Modifier.testTag(uiTarget("palette", "${row.key.kind}:${row.key.id}", model.selected?.let(::placeId).orEmpty(), action.name.lowercase())),
                ) { scope.launch { model.performPlaceAction(action, row.key) } }
            }
        },
        modifier = Modifier.testTag(uiTarget("palette", "${row.key.kind}:${row.key.id}", model.selected?.let(::placeId).orEmpty(), "row")),
    )
}

@Composable
private fun RecommendationRow(row: PaletteRecommendation, strings: Strings, model: IntegrationModel) {
    val scope = rememberCoroutineScope()
    PaletteCard(
        namePrefixWidth = KindMarkSize + 4.dp,
        name = {
            KindMark(row.key.kind)
            Spacer(Modifier.width(4.dp))
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            Text(
                strings.paletteInSet(row.installed, row.total, row.setName),
                style = MaterialTheme.typography.labelSmall,
                color = RuleblendTheme.extraColors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A recommendation is by construction an object this place does not hold: one offer, install.
            if (model.placeSupports(row.key)) {
                PaletteActionButton(PlaceAction.INSTALL, strings) {
                    scope.launch { model.performPlaceAction(PlaceAction.INSTALL, row.key) }
                }
            }
        },
    )
}

/** Names the group captured from this place. An existing name updates that group, never doubles it. */
@Composable
private fun GroupNameDialog(count: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf("") }
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.paletteSaveAsGroupTitle,
        confirmLabel = strings.actionSave,
        confirmEnabled = name.isNotBlank(),
        dismissLabel = strings.actionCancel,
        onConfirm = { onConfirm(name.trim()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                strings.paletteSaveAsGroupHint(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RuleblendOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().testTag("place-group-name"),
            )
        }
    }
}

/**
 * Both actions here carry full sentences as labels, and the palette column is narrow. Side by side
 * they leave the file name a column one character wide, so the actions get a line of their own and
 * wrap among themselves when even that line is too tight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundRow(file: PaletteFoundFile, strings: Strings, onAdopt: () -> Unit, onEdit: () -> Unit) {
    PaletteCardColumn {
        Text(
            file.name,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            strings.paletteFoundLines(file.lines),
            style = MaterialTheme.typography.labelSmall,
            color = RuleblendTheme.extraColors.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        ) {
            CompactPillButton(onClick = onEdit) {
                Text(strings.intEditFile, style = MaterialTheme.typography.labelMedium)
            }
            CompactPillButton(onClick = onAdopt, modifier = Modifier.testTag("place-found-adopt:${file.path}")) {
                Text(strings.actionAdopt, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Installing and removing are the two moves every palette row offers, so the narrow column shows
 * them as pictures; the writes that change what a row holds keep their words, as on the file preview.
 */
@Composable
private fun PaletteActionButton(
    action: PlaceAction,
    strings: Strings,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val icons = RuleblendTheme.icons
    when (action) {
        PlaceAction.INSTALL -> IconActionButton(icons.install, action.label(strings), onClick, modifier, size = PaletteIconButtonSize)
        PlaceAction.REMOVE -> IconActionButton(icons.delete, action.label(strings), onClick, modifier, size = PaletteIconButtonSize)
        else -> ActionButton(action, strings, modifier = modifier, onClick = onClick)
    }
}

/** A notch under the file preview's buttons: a palette row is one line among many, not a card to act on. */
private val PaletteIconButtonSize = 30.dp

/** In sync is the quiet state: a dot, with the word in its tooltip. What asks for a move keeps its pill. */
@Composable
private fun StatusPill(status: InstallStatus?, strings: Strings) = when (status) {
    InstallStatus.SYNCED -> RuleblendTooltip(strings.libStatusSynced) {
        StatusDot(
            RuleblendTheme.extraColors.success,
            // Twice the row's gap: the dot belongs to the name's side, not to the button it precedes.
            Modifier.padding(end = 6.dp).semantics { contentDescription = strings.libStatusSynced },
        )
    }
    InstallStatus.UPDATE_AVAILABLE -> StatusBadge(SyncBadge.UPDATE, strings.libStatusUpdate)
    InstallStatus.MODIFIED -> StatusBadge(SyncBadge.MODIFIED, strings.libStatusModified)
    null -> Unit
}

/** The card frame of [PaletteCard], stacked: for rows whose actions do not fit beside their text. */
@Composable
private fun PaletteCardColumn(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.cornerRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        content = content,
    )
}

/**
 * One library row: its name, and what can be done to it here. The two sit on one line while both
 * fit, and the badges and buttons take a line of their own as soon as the name would be squeezed
 * under [MinNameWidth] — a plain Row hands the leftover to the name and cuts it to "mul…", which is
 * exactly what this narrow column did to every name long enough to matter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteCard(
    name: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    namePrefixWidth: Dp = 0.dp,
) {
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.cornerRadius)
    val gap = 6.dp
    Layout(
        contents = listOf(
            { Row(verticalAlignment = Alignment.CenterVertically) { name() } },
            {
                // Wraps among themselves once they are on their own line: two buttons and a badge
                // still outgrow a column dragged down to its minimum width.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(gap, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    content = { trailing() },
                )
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) { (nameMeasurables, trailingMeasurables), constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        // Measured against the whole width first: what comes back is either its natural width, or a
        // wrapped block that already needs the line to itself.
        val trailingPlaceable = trailingMeasurables.first().measure(Constraints(maxWidth = width))
        val beside = width - trailingPlaceable.width - gapPx
        val stacked = beside < (MinNameWidth + namePrefixWidth).roundToPx()
        val namePlaceable = nameMeasurables.first()
            .measure(Constraints(maxWidth = if (stacked) width else beside))
        val height =
            if (stacked) namePlaceable.height + 4.dp.roundToPx() + trailingPlaceable.height
            else maxOf(namePlaceable.height, trailingPlaceable.height)
        layout(width, height) {
            if (stacked) {
                namePlaceable.place(0, 0)
                trailingPlaceable.place(width - trailingPlaceable.width, namePlaceable.height + 4.dp.roundToPx())
            } else {
                namePlaceable.place(0, (height - namePlaceable.height) / 2)
                trailingPlaceable.place(width - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
            }
        }
    }
}

/** Under this the name is a couple of characters and a tooltip, so the row stacks instead. */
private val MinNameWidth = 96.dp

@Composable
private fun MoreRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
