package dev.ruleblend.app.place

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.defaultScrollbarStyle
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.integration.IntegrationModel

import dev.ruleblend.app.library.CompactContextMenu
import dev.ruleblend.app.theme.DisclosureArrow
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.generated.resources.Res
import dev.ruleblend.app.generated.resources.plus
import dev.ruleblend.app.theme.FieldClearButton
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.app.util.pickDirectory
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.setOf
import dev.ruleblend.core.integration.ProjectTarget
import kotlinx.coroutines.launch

/**
 * Left column of the Place surface: filter, pinned and recent shortcuts, agent globals, project sets
 * and whatever is left ungrouped. Sets and pins are config state, so the column looks the same after
 * a restart; sections collapse per session only, since a fold is a glance, not a preference.
 */
@Composable
fun PlaceListColumn(
    model: IntegrationModel,
    groupsExpandedByDefault: Boolean = true,
    onRemoveProject: (ProjectTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    // Opening a place re-reads it from disk; adding a project writes the config. Both off the frame.
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var expansionOverrides by remember { mutableStateOf(emptySet<String>()) }
    var setPrompt by remember { mutableStateOf<SetPrompt?>(null) }

    val selectedId = model.selected?.let(::placeId)
    val sections = placeSections(
        targets = model.configuredPlaces,
        board = model.board,
        query = query,
        keep = selectedId,
        marks = model::dotMarks,
    )

    Column(modifier.fillMaxSize()) {
        RuleblendOutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(strings.placeFilter) },
            singleLine = true,
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            // Emptying the field by hand is the only way back to the full list, and a filter is
            // typed far more often than it is cleared character by character.
            trailingIcon = if (query.isEmpty()) null else {
                { FieldClearButton(strings.placeFilterClear) { query = "" } }
            },
            modifier = Modifier.testTag("place-filter").fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
        val listState = rememberLazyListState()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("place-list")) {
                sections.forEach { section ->
                    val open = groupsExpandedByDefault != (section.key in expansionOverrides)
                    item(key = "header:${section.key}") {
                        SectionHeader(
                            section = section,
                            open = open,
                            strings = strings,
                            onToggle = {
                                expansionOverrides = if (section.key in expansionOverrides) {
                                    expansionOverrides - section.key
                                } else {
                                    expansionOverrides + section.key
                                }
                            },
                            onRename = { setPrompt = SetPrompt(rename = section.name) },
                            onDelete = { section.name?.let(model::deleteSet) },
                        )
                    }
                    if (!open) return@forEach
                    items(section.places, key = { "${section.key}:${it.id}" }) { place ->
                        CompactContextMenu(
                            items = rowActions(
                                place = place,
                                model = model,
                                strings = strings,
                                onNewSet = { setPrompt = SetPrompt(assign = it) },
                                onRemoveProject = onRemoveProject,
                            ),
                        ) {
                            PlaceRow(
                                section = section.key,
                                place = place,
                                selected = place.id == selectedId,
                                onClick = { scope.launch { model.select(place.target) } },
                                onTogglePin = { model.togglePin(place.target) },
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.padding(vertical = 2.dp),
                style = defaultScrollbarStyle(),
            )
        }
        IconActionButton(
            icon = Res.drawable.plus,
            label = strings.intAddProject,
            onClick = { pickDirectory("Add project")?.let { dir -> scope.launch { model.addProject(dir) } } },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag("place-add-project"),
        )
    }

    setPrompt?.let { prompt ->
        SetNameDialog(
            initial = prompt.rename.orEmpty(),
            rename = prompt.rename != null,
            onDismiss = { setPrompt = null },
            onConfirm = { name ->
                val target = prompt.assign
                when {
                    prompt.rename != null -> model.renameSet(prompt.rename, name)
                    else -> {
                        model.createSet(name)
                        target?.let { model.assignToSet(it, name) }
                    }
                }
                setPrompt = null
            },
        )
    }
}

/** Open dialog state: a rename of [rename], or a new set that [assign] joins right away. */
private data class SetPrompt(val rename: String? = null, val assign: ProjectTarget? = null)

@Composable
private fun rowActions(
    place: PlaceEntry,
    model: IntegrationModel,
    strings: Strings,
    onNewSet: (ProjectTarget) -> Unit,
    onRemoveProject: (ProjectTarget) -> Unit,
): List<Pair<String, () -> Unit>> = buildList {
    add((if (place.pinned) strings.placeUnpin else strings.placePin) to { model.togglePin(place.target) })
    val project = place.target as? ProjectTarget ?: return@buildList
    val current = model.board.setOf(project.dir.projectKey())
    model.board.sets.filter { it.name != current }.forEach { set ->
        add(strings.placeMoveToSet(set.name) to { model.assignToSet(project, set.name) })
    }
    add(strings.placeNewSet to { onNewSet(project) })
    if (current != null) add(strings.placeRemoveFromSet to { model.assignToSet(project, null) })
    add(strings.ctxRemoveProject to { onRemoveProject(project) })
}

@Composable
private fun SectionHeader(
    section: PlaceSection,
    open: Boolean,
    strings: Strings,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = when (section.kind) {
        PlaceSectionKind.PINNED -> strings.placePinned
        PlaceSectionKind.RECENT -> strings.placeRecent
        PlaceSectionKind.AGENTS -> strings.intAgents
        PlaceSectionKind.SET -> section.name.orEmpty()
        PlaceSectionKind.UNGROUPED -> strings.placeUngrouped
    }
    val actions = when (section.kind) {
        PlaceSectionKind.SET -> listOf(strings.placeRenameSet to onRename, strings.placeDeleteSet to onDelete)
        else -> emptyList()
    }
    CompactContextMenu(items = actions) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(uiTarget("place-set", section.key, "header", "select"))
                .clickable(onClick = onToggle)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 3.dp),
        ) {
            DisclosureArrow(open, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title.uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(section.total.toString(), style = MaterialTheme.typography.labelMedium, color = RuleblendTheme.extraColors.faint)
        }
    }
}

/**
 * A place row. The star both reports and sets the pin: it stays visible while the place is pinned
 * and appears under the pointer otherwise, so the shortcut is reachable without the context menu.
 * Its slot is always there, so a name does not shift when the pointer crosses the row.
 */
@Composable
private fun PlaceRow(section: String, place: PlaceEntry, selected: Boolean, onClick: () -> Unit, onTogglePin: () -> Unit) {
    val strings = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
    val foreground =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .hoverable(interaction)
            .testTag(uiTarget("place", place.id, section, "select"))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
    ) {
        StatusDot(place.marks)
        Text(
            place.name,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (place.pinned || hovered) {
                RuleblendTooltip(if (place.pinned) strings.placeUnpin else strings.placePin) {
                    Text(
                        if (place.pinned) "★" else "☆",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (place.pinned) {
                            RuleblendTheme.extraColors.warning
                        } else {
                            RuleblendTheme.extraColors.faint
                        },
                        modifier = Modifier.clickable(onClick = onTogglePin),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetNameDialog(
    initial: String,
    rename: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(initial) }
    RuleblendDialog(
        onDismiss = onDismiss,
        title = if (rename) strings.placeRenameSet else strings.placeNewSet,
        confirmLabel = if (rename) strings.actionSave else strings.actionCreate,
        confirmEnabled = name.isNotBlank(),
        dismissLabel = strings.actionCancel,
        onConfirm = { onConfirm(name.trim()) },
    ) {
        RuleblendOutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth().testTag("place-set-name"),
        )
    }
}
