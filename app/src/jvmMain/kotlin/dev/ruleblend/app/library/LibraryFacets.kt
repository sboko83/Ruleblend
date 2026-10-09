package dev.ruleblend.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.model.ALL_GROUP_ID

@Composable
internal fun Facets(
    catalog: LibraryCatalog,
    usage: LibraryUsageIndex,
    sourceUpdateIds: Set<String>,
    sourceUpdatesChecked: Boolean,
    filters: LibraryFilters,
    onFiltersChange: (LibraryFilters) -> Unit,
    onTypeFiltersChange: (LibraryFilters) -> Unit = onFiltersChange,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    LazyColumn(modifier.testTag("library-facets").padding(horizontal = 10.dp, vertical = 8.dp)) {
        item { FacetHeader(strings.libTypes) }
        items(LibraryObjectKind.entries) { kind ->
            val label = when (kind) {
                LibraryObjectKind.RULE -> strings.libTypeRule
                LibraryObjectKind.SUBAGENT -> strings.libTypeSubagent
                LibraryObjectKind.SKILL -> strings.libSkills
                LibraryObjectKind.MCP -> strings.libMcp
                LibraryObjectKind.GROUP -> strings.libGroups
                LibraryObjectKind.PROFILE -> strings.libProfiles
            }
            FacetRow(
                label,
                catalog.count(kind),
                kind in filters.kinds,
                "type:${kind.name}",
                onClick = { onTypeFiltersChange(filters.copy(kinds = setOf(kind))) },
                onSecondaryClick = { onTypeFiltersChange(filters.copy(kinds = filters.kinds.toggle(kind))) },
            )
        }
        item { FacetHeader(strings.libGroups) }
        item {
            FacetRow(strings.libAllGroup, catalog.objects.size, filters.groupId == null, "group:all") {
                onFiltersChange(filters.copy(groupId = null))
            }
        }
        items(catalog.groups.filter { it.id != ALL_GROUP_ID }, key = { it.id }) { group ->
            FacetRow(group.name, catalog.groupCount(group.id), filters.groupId == group.id, "group:${group.id}") {
                onFiltersChange(filters.copy(groupId = group.id.toggle(filters.groupId)))
            }
        }
        item { FacetHeader(strings.fieldScope) }
        item {
            FacetRow(strings.libGlobalScope, catalog.objects.count { it.scope == null }, filters.scope == LibraryScopeFilter.GLOBAL, "scope:global") {
                onFiltersChange(filters.copy(scope = LibraryScopeFilter.GLOBAL.toggle(filters.scope)))
            }
        }
        item {
            FacetRow(strings.libProjectScoped, catalog.objects.count { it.scope != null }, filters.scope == LibraryScopeFilter.PROJECT, "scope:project") {
                onFiltersChange(filters.copy(scope = LibraryScopeFilter.PROJECT.toggle(filters.scope)))
            }
        }
        // Hidden until a scan answered it: "unused · 304" would be a lie about an unscanned library.
        if (!usage.isEmpty) {
            item { FacetHeader(strings.libUsage) }
            item {
                FacetRow(
                    strings.libUsageInstalled,
                    catalog.objects.count { usage.isInstalled(it.key) },
                    filters.usage == LibraryUsageFilter.INSTALLED,
                    "usage:installed",
                ) { onFiltersChange(filters.copy(usage = LibraryUsageFilter.INSTALLED.toggle(filters.usage))) }
            }
            item {
                FacetRow(
                    strings.libUsageUnused,
                    catalog.objects.count { !usage.isInstalled(it.key) },
                    filters.usage == LibraryUsageFilter.UNUSED,
                    "usage:unused",
                ) { onFiltersChange(filters.copy(usage = LibraryUsageFilter.UNUSED.toggle(filters.usage))) }
            }
        }
        item { FacetHeader(strings.libSource) }
        item {
            FacetRow(
                strings.libLocalSkills,
                catalog.objects.count { it.key.kind in setOf(LibraryObjectKind.SKILL, LibraryObjectKind.SUBAGENT) && !it.importedFromGit && !it.builtIn },
                filters.source == LibrarySourceFilter.LOCAL,
                "source:local",
            ) { onFiltersChange(filters.copy(source = LibrarySourceFilter.LOCAL.toggle(filters.source))) }
        }
        item {
            FacetRow(
                strings.libImportedSkills,
                catalog.objects.count { it.importedFromGit },
                filters.source == LibrarySourceFilter.GIT,
                "source:git",
            ) { onFiltersChange(filters.copy(source = LibrarySourceFilter.GIT.toggle(filters.source))) }
        }
        if (sourceUpdatesChecked) {
            item {
                FacetRow(
                    strings.libUpdateAvailable,
                    sourceUpdateIds.size,
                    filters.updatesAvailable,
                    "source:update",
                ) { onFiltersChange(filters.copy(updatesAvailable = !filters.updatesAvailable)) }
            }
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private fun <T> T.toggle(current: T?): T? = if (this == current) null else this

@Composable
private fun FacetHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
}

@Composable
private fun FacetRow(
    label: String,
    count: Int,
    selected: Boolean,
    id: String,
    onSecondaryClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-facet:$id")
            .background(
                if (selected) RuleblendTheme.extraColors.accentBackground else MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.small,
            )
            .then(
                onSecondaryClick?.let { callback ->
                    Modifier.pointerInput(callback) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    callback()
                                }
                            }
                        }
                    }
                } ?: Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = RuleblendTheme.extraColors.faint)
    }
}

@Composable
internal fun ActiveFilters(
    catalog: LibraryCatalog,
    filters: LibraryFilters,
    onChange: (LibraryFilters) -> Unit,
) {
    val strings = LocalStrings.current
    if (filters == LibraryFilters(query = filters.query)) return
    // Bottom padding, not a trailing spacer: a spacer is one more item in the flow, which leaves the
    // chips resting on the divider below them.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .testTag(LibraryActiveFiltersTag)
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
    ) {
        filters.kinds.forEach { kind ->
            val label = when (kind) {
                LibraryObjectKind.RULE -> strings.libTypeRule
                LibraryObjectKind.SUBAGENT -> strings.libTypeSubagent
                LibraryObjectKind.SKILL -> strings.libSkills
                LibraryObjectKind.MCP -> strings.libMcp
                LibraryObjectKind.GROUP -> strings.libGroups
                LibraryObjectKind.PROFILE -> strings.libProfiles
            }
            FilterChip(label) { onChange(filters.copy(kinds = filters.kinds - kind)) }
        }
        filters.groupId?.let { groupId ->
            val label = catalog.groups.find { it.id == groupId }?.name ?: groupId
            FilterChip(label) { onChange(filters.copy(groupId = null)) }
        }
        filters.scope?.let {
            FilterChip(if (it == LibraryScopeFilter.GLOBAL) strings.libGlobalScope else strings.libProjectScoped) {
                onChange(filters.copy(scope = null))
            }
        }
        filters.source?.let {
            FilterChip(if (it == LibrarySourceFilter.LOCAL) strings.libLocalSkills else strings.libImportedSkills) {
                onChange(filters.copy(source = null))
            }
        }
        filters.usage?.let {
            FilterChip(if (it == LibraryUsageFilter.INSTALLED) strings.libUsageInstalled else strings.libUsageUnused) {
                onChange(filters.copy(usage = null))
            }
        }
        if (filters.updatesAvailable) {
            FilterChip(strings.libUpdateAvailable) { onChange(filters.copy(updatesAvailable = false)) }
        }
    }
}

@Composable
private fun FilterChip(label: String, onClick: () -> Unit) {
    Text(
        "$label  ×",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(RuleblendTheme.extraColors.accentBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** The strip of active filter chips; tagged so the room under it can be measured. */
internal const val LibraryActiveFiltersTag = "library-active-filters"
