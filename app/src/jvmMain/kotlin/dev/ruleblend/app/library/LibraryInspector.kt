package dev.ruleblend.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.StatusBadge
import dev.ruleblend.app.theme.SyncBadge
import dev.ruleblend.app.theme.VersionPill
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.GitSkillSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** The type letter at the head of the inspector; tagged so its alignment can be measured. */
internal const val LibraryInspectorMarkTag = "library-inspector-mark"

/** The body block of the inspector, which opens the editor. */
internal const val LibraryInspectorBodyTag = "library-inspector-body"

/** A row of the version history, which opens that revision's diff. */
internal const val LibraryRevisionRowTag = "library-revision-row"

/** Opens a fork against the imported skill that now represents its upstream. */
internal const val LibraryCompareUpstreamTag = "library-compare-upstream"

@Composable
internal fun Inspector(
    model: LibraryModel,
    item: LibraryObject?,
    onEdit: (LibraryObjectKey) -> Unit,
    onDelete: (LibraryObjectKey) -> Unit,
    onCompareUpstream: (LibraryObjectKey, LibraryObjectKey) -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    if (item == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(strings.libSelectObject, color = RuleblendTheme.extraColors.faint)
        }
        return
    }
    val sites = model.usage.sitesOf(item.key)
    val upstream = item.forkedFrom?.let { base ->
        model.catalog.objects.find { candidate ->
            candidate.key.kind == item.key.kind && candidate.sourceAssistant == item.forkAssistant &&
                candidate.gitSource?.repository == base.repository && candidate.gitSource.path == base.path
        }
    }
    val upstreamAhead = item.forkedFrom?.let { base ->
        upstream?.gitSource?.revision?.let { revision -> revision != base.revision }
    } ?: false
    var allSites by remember(item.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The reserved group is derived, not kept: it cannot be edited, deleted or installed, and it has
    // no history of its own. Everything below the title would be a fact about some other object.
    val reserved = item.key.id == ALL_GROUP_ID
    // A built-in ships with the app: there is no library file behind it and so no history, and the
    // buttons at the bottom would offer to edit and delete something the user does not own.
    LaunchedEffect(item.key) { if (!reserved && !item.builtIn) model.loadHistory(item.key) }
    LazyColumn(
        modifier.testTag(uiTarget("library", "${item.key.kind}:${item.key.id}", "inspector", "selected")).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // The mark, the name and the version are one line: the type letter already says what
            // this is, so the word beside it said nothing, and a version pushed onto a line of its
            // own read as a second fact rather than as part of the title.
            val titleStyle = MaterialTheme.typography.titleLarge
            val titleLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                // Centred against the first line rather than the block: a name long enough to wrap
                // must not drag the mark down with it.
                Box(
                    Modifier.height(titleLine).testTag(LibraryInspectorMarkTag),
                    contentAlignment = Alignment.Center,
                ) {
                    KindMark(item.key.kind)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (item.favorite) FavoriteMark()
                        Text(
                            item.name,
                            style = titleStyle,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        VersionPill("v${item.version}")
                    }
                    val notes = listOfNotNull(
                        item.scope?.let { strings.libPinnedTo(projectScopeLabel(it)) },
                        strings.libAutoMaintained.takeIf { item.key.id == ALL_GROUP_ID },
                        strings.libBuiltIn.takeIf { item.builtIn },
                    )
                    if (notes.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            notes.forEach { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (item.description.isNotBlank()) {
            item { Text(item.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (item.builtIn) {
            item {
                Text(
                    strings.libBuiltInManaged,
                    style = MaterialTheme.typography.labelLarge,
                    color = RuleblendTheme.extraColors.faint,
                )
            }
            item { InspectorHeader(strings.libBody) }
            item {
                // Read-only on purpose: there is no library file behind this text, so the click that
                // opens the editor everywhere else has nothing to open.
                Text(
                    item.content,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                        .testTag(LibraryInspectorBodyTag)
                        .padding(8.dp),
                )
            }
            return@LazyColumn
        }
        if (reserved) {
            item { InspectorHeader(strings.libMembersCount(item.memberKeys.size)) }
            return@LazyColumn
        }
        if (item.key.kind == LibraryObjectKind.GROUP || item.key.kind == LibraryObjectKind.PROFILE) {
            item { InspectorHeader(strings.libMembersCount(item.memberKeys.size)) }
            items(item.memberKeys.take(10), key = { "member:${it.kind}:${it.id}" }) { member ->
                val objectItem = model.catalog.objects.find { it.key == member }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindMark(member.kind)
                    Text(objectItem?.name ?: member.id, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (item.memberKeys.size > 10) {
                item {
                    Text(
                        "+ ${item.memberKeys.size - 10}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        } else if (item.content.isNotBlank()) {
            item { InspectorHeader(strings.libBody) }
            item {
                // The body is the shortest way to the editor: a reader who is looking at the text is
                // already deciding whether to change it, and the Edit button is a screenful below.
                RuleblendTooltip(strings.libBodyOpenEditor) {
                    BlockContextMenu(item.copyText, onEdit = { onEdit(item.key) }) {
                        Text(
                            item.content,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 9,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                .testTag(LibraryInspectorBodyTag)
                                .clickable { onEdit(item.key) }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
        item.gitSource?.let { source -> item { GitProvenance(source) } }
        item.forkedFrom?.let { base ->
            item {
                ForkProvenance(
                    base = base,
                    upstreamAhead = upstreamAhead,
                    onCompare = upstream?.takeIf { upstreamAhead }?.let { candidate ->
                        { onCompareUpstream(item.key, candidate.key) }
                    },
                )
            }
        }
        // A profile is attached to projects in the next Place step. Calling it "not installed" here
        // would be false: it is neither an installable file nor a standalone write.
        if (item.key.kind != LibraryObjectKind.PROFILE) {
            item { InspectorHeader(strings.libInstalledIn(sites.size)) }
            if (sites.isEmpty()) {
                item {
                    Text(
                        strings.libNotInstalled,
                        style = MaterialTheme.typography.bodyLarge,
                        color = RuleblendTheme.extraColors.faint,
                    )
                }
            } else {
                val shown = if (allSites) sites else sites.take(INSPECTOR_PLACE_LIMIT)
                items(shown, key = { "place:${it.placeId}" }) { site ->
                    PlaceRow(site) { onOpenPlace(site.placeId) }
                }
                if (sites.size > shown.size) {
                    item {
                        Text(
                            strings.libMoreCount(sites.size - shown.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { allSites = true }.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
        item { InspectorHeader(strings.libHistory) }
        if (model.history.isEmpty()) {
            item {
                Text(
                    strings.libHistoryEmpty,
                    style = MaterialTheme.typography.bodyLarge,
                    color = RuleblendTheme.extraColors.faint,
                )
            }
        } else {
            items(model.history, key = { "revision:${it.id}" }) { revision ->
                // Date, message, size of the change — and the diff itself one click away, in a window
                // wide enough for source lines.
                RuleblendTooltip(strings.libDiffOpen) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("$LibraryRevisionRowTag:${revision.id}")
                            .clickable { scope.launch { model.showDiff(revision) } }
                            .padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${revision.time.formatDay()} · ${revision.message}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        RevisionCounts(revision)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                IconActionButton(
                    icon = RuleblendTheme.icons.edit,
                    label = if (item.importedFromGit) strings.libForkToEdit else strings.libEditFocus,
                    onClick = { onEdit(item.key) },
                    modifier = Modifier.testTag("library-inspector-edit"),
                    colors = ButtonDefaults.buttonColors(),
                )
                IconActionButton(
                    icon = RuleblendTheme.icons.delete,
                    label = strings.actionDelete,
                    onClick = { onDelete(item.key) },
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("library-inspector-delete"),
                )
            }
        }
    }
}

/** A fork stays editable, but retains enough provenance to compare it with a later upstream import. */
@Composable
private fun ForkProvenance(base: GitSkillSource, upstreamAhead: Boolean, onCompare: (() -> Unit)?) {
    val strings = LocalStrings.current
    val mono = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily)
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) {
        Text(strings.libForkBaseTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            base.repository,
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            base.path,
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "@ ${base.revision}",
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (upstreamAhead) {
            Text(
                strings.libForkUpstreamAhead,
                style = MaterialTheme.typography.labelMedium,
                color = RuleblendTheme.extraColors.warning,
            )
        }
        onCompare?.let { action ->
            CompactOutlinedButton(
                onClick = action,
                modifier = Modifier.testTag(LibraryCompareUpstreamTag),
            ) { Text(strings.libCompareUpstream) }
        }
    }
}

private const val INSPECTOR_PLACE_LIMIT = 8

/** An imported skill stays a copy of an upstream revision: it is updated, never edited in place. */
@Composable
private fun GitProvenance(source: GitSkillSource) {
    val strings = LocalStrings.current
    val mono = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(8.dp),
    ) {
        Text(strings.libGitSourceTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            source.repository,
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "@ ${source.revision}",
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(strings.libGitImmutable, style = MaterialTheme.typography.labelMedium, color = RuleblendTheme.extraColors.warning)
    }
}

@Composable
private fun PlaceRow(site: LibraryInstallSite, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val (badge, label) = when (site.install.status) {
        InstallStatus.SYNCED -> SyncBadge.SYNCED to strings.libStatusSynced
        InstallStatus.UPDATE_AVAILABLE -> SyncBadge.UPDATE to strings.libStatusUpdate
        InstallStatus.MODIFIED -> SyncBadge.MODIFIED to strings.libStatusModified
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("library-usage:${site.placeId}")
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            site.placeName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusBadge(badge, label)
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

internal fun Instant.formatDay(): String = DAY_FORMAT.format(this)

@Composable
private fun InspectorHeader(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 5.dp))
}
