package dev.ruleblend.app.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import dev.ruleblend.app.util.uiTarget
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.navigation.PaneMinWidth
import dev.ruleblend.app.navigation.fitPanes
import dev.ruleblend.app.navigation.paneMaxWidth
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.generated.resources.Res
import dev.ruleblend.app.generated.resources.plus
import dev.ruleblend.app.generated.resources.upload
import dev.ruleblend.app.generated.resources.folder_plus
import dev.ruleblend.app.generated.resources.combine
import dev.ruleblend.app.theme.DisclosureArrow
import dev.ruleblend.app.theme.FieldClearButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SplitPane
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.model.ALL_GROUP_ID
import kotlin.math.roundToInt

@Composable
internal fun LibraryCatalogScreen(
    model: LibraryModel,
    onNew: (LibraryObjectKind) -> Unit,
    onImportSkills: () -> Unit,
    onEdit: (LibraryObjectKey) -> Unit,
    onDelete: (LibraryObjectKey) -> Unit,
    onCompare: (LibraryObjectKey) -> Unit,
    onCompareUpstream: (LibraryObjectKey, LibraryObjectKey) -> Unit,
    onOpenPlace: (String) -> Unit,
    onInstallMarked: () -> Unit,
    onAddMarkedToGroup: () -> Unit,
    onExportMarked: () -> Unit,
    onCombineMarkedRules: () -> Unit,
    groupsExpandedByDefault: Boolean,
    sourceUpdateIds: Set<String>,
    sourceUpdatesChecked: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val catalog = model.catalog
    val usage = model.usage
    var filters by remember { mutableStateOf(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE))) }
    var usesDefaultTypeFilter by remember { mutableStateOf(true) }
    var grouping by remember { mutableStateOf(LibraryGrouping.TYPE) }
    var expansionOverrides by remember { mutableStateOf(emptySet<String>()) }
    var newMenu by remember { mutableStateOf(false) }
    val categories = catalog.categories(grouping, filters, usage, sourceUpdateIds)
    val forcedOpen = filters.query.isNotBlank()
    // A query that hides the selected object empties the inspector, as a facet does; a query that
    // still lists it keeps it open, so typing to find a neighbour does not drop what is being read.
    fun search(next: LibraryFilters) {
        filters = next
        val key = model.selectedCatalogKey ?: return
        val listed = catalog.categories(grouping, next, usage, sourceUpdateIds).any { category ->
            category.items.any { it.key == key }
        }
        if (!listed) model.clearCatalogSelection()
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Same rule as Place: the list keeps a readable width, the side columns shrink and, if the
        // window is smaller than all three, facets go first — every facet is also a query in the search
        // field, while the inspector is the only place an object shows its content. Both widths are
        // the user's to set, by the same drag as Place's columns, and outlive the session.
        val available = maxWidth
        val facetsRequest = model.columnWidths.libraryFacets.dp
        val inspectorRequest = model.columnWidths.libraryInspector.dp
        val fit = fitPanes(available = available, leading = facetsRequest, trailing = inspectorRequest)
        Row(Modifier.fillMaxSize()) {
            fit.leading?.let { width ->
                SplitPane(
                    width = width.value.roundToInt(),
                    onWidthChange = { model.saveColumnWidth(LibraryColumn.FACETS, it) },
                    min = PaneMinWidth.value.roundToInt(),
                    max = paneMaxWidth(available, inspectorRequest).value.roundToInt(),
                ) {
                    Facets(
                        catalog = catalog,
                        usage = usage,
                        sourceUpdateIds = sourceUpdateIds,
                        sourceUpdatesChecked = sourceUpdatesChecked,
                        filters = filters,
                        onFiltersChange = { picked ->
                            val normalized = if (usesDefaultTypeFilter && picked.kinds == filters.kinds) {
                                picked.copy(kinds = emptySet())
                            } else {
                                picked
                            }
                            // Project rules are read project by project: by type or by group every
                            // one of them lands in a single heap, so the facet that narrows the list
                            // to them also switches how it is cut. Turning it off returns the list
                            // to types rather than leaving one section per project behind.
                            if (normalized.scope != filters.scope) {
                                grouping = when {
                                    normalized.scope == LibraryScopeFilter.PROJECT -> LibraryGrouping.AREA
                                    grouping == LibraryGrouping.AREA -> LibraryGrouping.TYPE
                                    else -> grouping
                                }
                            }
                            filters = normalized
                            usesDefaultTypeFilter = false
                            // A facet re-asks the question the list answers, so the answer beside it
                            // starts empty: keeping the previous object would leave the inspector
                            // describing something the new list may not even hold.
                            model.clearCatalogSelection()
                        },
                        onTypeFiltersChange = { picked ->
                            if (picked == filters) return@Facets
                            filters = picked
                            usesDefaultTypeFilter = false
                            // A type facet changes the list question, so the answer beside it starts
                            // empty rather than describing an object hidden by the new type.
                            model.clearCatalogSelection()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuleblendOutlinedTextField(
                            value = filters.query,
                            onValueChange = { query ->
                                search(
                                    if (query.isNotBlank() && usesDefaultTypeFilter) {
                                        usesDefaultTypeFilter = false
                                        filters.copy(query = query, kinds = emptySet())
                                    } else {
                                        filters.copy(query = query)
                                    },
                                )
                            },
                            placeholder = { Text(strings.libSearchObjects(catalog.objects.size)) },
                            singleLine = true,
                            colors = ruleblendFieldColors(),
                            contentPadding = RuleblendFieldContentPadding,
                            // The search narrows every list on this screen, so getting back to the
                            // whole library must be one click, not a held backspace.
                            trailingIcon = if (filters.query.isEmpty()) null else {
                                { FieldClearButton(strings.libSearchClear) { search(filters.copy(query = "")) } }
                            },
                            modifier = Modifier.weight(1f).testTag("library-search"),
                        )
                        Box {
                            IconActionButton(Res.drawable.plus, strings.libNewObject, onClick = { newMenu = true }, modifier = Modifier.testTag("library-new"), colors = ButtonDefaults.buttonColors())
                            DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                                listOf(
                                    LibraryObjectKind.RULE to strings.libTypeRule,
                                    LibraryObjectKind.SUBAGENT to strings.libTypeSubagent,
                                    LibraryObjectKind.SKILL to strings.libSkills,
                                    LibraryObjectKind.MCP to strings.libMcp,
                                    LibraryObjectKind.GROUP to strings.libGroups,
                                    LibraryObjectKind.PROFILE to strings.libProfiles,
                                ).forEach { (kind, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { newMenu = false; onNew(kind) },
                                        modifier = Modifier.testTag("library-new-${kind.name.lowercase()}"),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(strings.libImportSkillsAction) },
                                    onClick = { newMenu = false; onImportSkills() },
                                    modifier = Modifier.testTag("library-import-git"),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Segment(strings.libByGroup, grouping == LibraryGrouping.GROUP, "group") { grouping = LibraryGrouping.GROUP }
                        Segment(strings.libByType, grouping == LibraryGrouping.TYPE, "type") { grouping = LibraryGrouping.TYPE }
                        Segment(strings.libByArea, grouping == LibraryGrouping.AREA, "area") { grouping = LibraryGrouping.AREA }
                    }
                }
                ActiveFilters(catalog, filters) {
                    filters = it
                    usesDefaultTypeFilter = false
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                val listState = rememberLazyListState()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        categories.filter { it.items.isNotEmpty() || filters == LibraryFilters() }.forEach { category ->
                            item(key = "header:${category.key}") {
                                CategoryHeader(
                                    category = category,
                                    open = forcedOpen || (groupsExpandedByDefault != (category.key in expansionOverrides)),
                                    onToggle = {
                                        expansionOverrides = if (category.key in expansionOverrides) {
                                            expansionOverrides - category.key
                                        } else {
                                            expansionOverrides + category.key
                                        }
                                    },
                                )
                            }
                            if (forcedOpen || (groupsExpandedByDefault != (category.key in expansionOverrides))) {
                                items(category.items, key = { "${category.key}:${it.key.kind}:${it.key.id}" }) { item ->
                                    ObjectRow(
                                        item = item,
                                        current = item.key == model.selectedCatalogKey,
                                        marked = item.key in model.marked,
                                        // A built-in is not installed from the library, so the
                                        // usage index has no row for it — and "0 places" would read
                                        // as unused rather than as unknown.
                                        installs = if (usage.isEmpty || item.builtIn) null else usage.installCount(item.key),
                                        onClick = { model.selectCatalogObject(item.key) },
                                        onOpen = { onEdit(item.key) },
                                        onMark = { model.toggleMark(item.key) },
                                        onCompare = { onCompare(item.key) },
                                    )
                                }
                            }
                        }
                        if (categories.all { it.items.isEmpty() }) {
                            item {
                                Text(
                                    strings.libNoMatches,
                                    color = RuleblendTheme.extraColors.faint,
                                    modifier = Modifier.padding(30.dp),
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.width(8.dp).padding(vertical = 2.dp),
                        style = defaultScrollbarStyle(),
                    )
                }
                if (model.marked.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    BulkBar(
                        count = model.marked.size,
                        canCombine = model.marked.size >= 2 && model.marked.all { it.kind == LibraryObjectKind.RULE },
                        onInstall = onInstallMarked,
                        onAddToGroup = onAddMarkedToGroup,
                        onExport = onExportMarked,
                        onCombine = onCombineMarkedRules,
                        onClear = model::clearMarks,
                    )
                }
            }
            fit.trailing?.let { width ->
                SplitPane(
                    width = width.value.roundToInt(),
                    onWidthChange = { model.saveColumnWidth(LibraryColumn.INSPECTOR, it) },
                    min = PaneMinWidth.value.roundToInt(),
                    max = paneMaxWidth(available, fit.leading ?: 0.dp).value.roundToInt(),
                    dividerAtStart = true,
                ) {
                    Inspector(
                        model = model,
                        item = model.selectedCatalogKey?.let { key -> catalog.objects.find { it.key == key } },
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onCompareUpstream = onCompareUpstream,
                        onOpenPlace = onOpenPlace,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, id: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            // Clipped first, so the hover fill keeps the tab's corners instead of squaring them off.
            .clip(MaterialTheme.shapes.small)
            .testTag("library-grouping:$id")
            .background(
                if (selected) RuleblendTheme.extraColors.accentBackground else MaterialTheme.colorScheme.surface,
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun CategoryHeader(category: LibraryCategory, open: Boolean, onToggle: () -> Unit) {
    val strings = LocalStrings.current
    val label = when (category.key) {
        "type:RULE" -> strings.libTypeRule
        "type:SKILL" -> strings.libSkills
        "type:MCP", "mcp" -> strings.libMcp
        "type:GROUP", "groups" -> strings.libGroups
        "ungrouped" -> strings.libUngrouped
        GLOBAL_SCOPE_CATEGORY -> strings.libGlobalScope
        else -> category.name
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        DisclosureArrow(open)
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(category.items.size.toString(), style = MaterialTheme.typography.labelMedium, color = RuleblendTheme.extraColors.faint)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** Test handle for the row tick: the checkbox carries no text of its own to select it by. */
internal const val LibraryRowMarkTag = "library-row-mark"

/**
 * The bulk bar of the catalog. Shown only with a selection: at rest the list must not carry a strip
 * of actions that would do nothing.
 */
@Composable
private fun BulkBar(
    count: Int,
    canCombine: Boolean,
    onInstall: () -> Unit,
    onAddToGroup: () -> Unit,
    onExport: () -> Unit,
    onCombine: () -> Unit,
    onClear: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            strings.libSelectedCount(count),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        IconActionButton(RuleblendTheme.icons.install, strings.libBulkInstall, onInstall, Modifier.testTag("library-bulk-install"), colors = ButtonDefaults.buttonColors())
        IconActionButton(Res.drawable.folder_plus, strings.libBulkAddToGroup, onAddToGroup)
        IconActionButton(Res.drawable.upload, strings.libBulkExport, onExport)
        if (canCombine) {
            IconActionButton(
                icon = Res.drawable.combine,
                label = strings.libBulkCombineRules,
                onClick = onCombine,
                modifier = Modifier.testTag("library-bulk-combine-rules"),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            strings.libBulkClear,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ObjectRow(
    item: LibraryObject,
    current: Boolean,
    marked: Boolean,
    installs: Int?,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onMark: () -> Unit,
    onCompare: () -> Unit,
) {
    val strings = LocalStrings.current
    // A single click selects, a double click opens — the same pair a file list has anywhere else.
    // The reserved group and the built-ins are the exception: they have no editor to open.
    val editable = item.key.id != ALL_GROUP_ID && !item.builtIn
    CompactContextMenu(
        // Every text-bearing kind can be compared; only a group has no body of its own.
        items = if (item.key.kind != LibraryObjectKind.GROUP && item.key.kind != LibraryObjectKind.PROFILE) {
            buildList<Pair<String, () -> Unit>> {
                add(strings.setCopy to { dev.ruleblend.app.util.copyToClipboard(item.copyText) })
                if (editable) add(strings.libEditFocus to onOpen)
                add(strings.compareAction to onCompare)
            }
        } else {
            emptyList()
        },
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (current) RuleblendTheme.extraColors.accentBackground else MaterialTheme.colorScheme.surface)
            .testTag(uiTarget("library", "${item.key.kind}:${item.key.id}", "catalog", "select"))
            .combinedClickable(onClick = onClick, onDoubleClick = if (editable) onOpen else null)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        // Bulk install, group and export all act on library objects; a built-in is none of those,
        // so its row keeps the column and loses the box.
        if (item.builtIn) {
            Spacer(Modifier.size(16.dp))
        } else {
            SquareCheckbox(checked = marked, onCheckedChange = { onMark() }, modifier = Modifier.testTag(LibraryRowMarkTag))
        }
        KindMark(item.key.kind)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.width(188.5.dp),
        ) {
            if (item.favorite) FavoriteMark()
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "v${item.version}",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
            color = RuleblendTheme.extraColors.faint,
        )
        Text(
            item.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        installs?.let { count ->
            Text(
                if (count == 0) strings.libUnusedMark else strings.libInPlaces(count),
                style = MaterialTheme.typography.labelMedium,
                color = if (count == 0) RuleblendTheme.extraColors.faint else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

/** The star a rule carries once it is a favorite — same glyph the editor toggles. */
@Composable
internal fun FavoriteMark() {
    Text("★", style = MaterialTheme.typography.labelMedium, color = RuleblendTheme.extraColors.warning)
}

@Composable
internal fun KindMark(kind: LibraryObjectKind) {
    val (letter, background, foreground) = when (kind) {
        LibraryObjectKind.RULE -> Triple("R", RuleblendTheme.extraColors.accentBackground, MaterialTheme.colorScheme.primary)
        LibraryObjectKind.SUBAGENT -> Triple("A", RuleblendTheme.extraColors.subagentBackground, RuleblendTheme.extraColors.subagent)
        LibraryObjectKind.SKILL -> Triple("S", RuleblendTheme.extraColors.successBackground, RuleblendTheme.extraColors.success)
        LibraryObjectKind.MCP -> Triple("M", RuleblendTheme.extraColors.infoBackground, RuleblendTheme.extraColors.info)
        LibraryObjectKind.GROUP -> Triple("G", RuleblendTheme.extraColors.warningBackground, RuleblendTheme.extraColors.warning)
        LibraryObjectKind.PROFILE -> Triple("P", RuleblendTheme.extraColors.accentBackground, MaterialTheme.colorScheme.primary)
    }
    Box(
        Modifier.size(KindMarkSize).background(background, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.titleMedium, color = foreground)
    }
}

internal val KindMarkSize = 24.dp
