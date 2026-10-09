package dev.ruleblend.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.navigation.fitPanes
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.StatusBadge
import dev.ruleblend.app.theme.SyncBadge
import dev.ruleblend.app.theme.VersionPill
import dev.ruleblend.app.split.SplitDialog
import dev.ruleblend.app.util.FailureDialog
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill

@Composable
fun LibraryScreen(
    model: LibraryModel,
    modifier: Modifier = Modifier,
    onOpenPlace: (String) -> Unit = {},
    /** An object opened from Place starts in the editor and returns there instead of to the catalog. */
    openEditorKey: LibraryObjectKey? = null,
    editorBackLabel: String? = null,
    onEditorBack: (() -> Unit)? = null,
    onBlockSaved: suspend (Block) -> Unit = {},
    onSkillSaved: suspend (Skill) -> Unit = {},
    compareKey: LibraryObjectKey? = null,
    onCompareOpened: () -> Unit = {},
    groupsExpandedByDefault: Boolean = true,
    sourceUpdateIds: Set<String> = emptySet(),
    sourceUpdatesChecked: Boolean = false,
    /** `null` where translation is not available, which also keeps UI tests free of a helper. */
    translation: TranslationModel? = null,
) {
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var splitting by remember { mutableStateOf<Block?>(null) }
    var comparing by remember { mutableStateOf<CompareRequest?>(null) }
    var focusEditor by remember { mutableStateOf(false) }
    val strings = LocalStrings.current

    // Home can lead a resolved remote conflict straight into the existing compare tool.
    LaunchedEffect(compareKey) {
        compareKey?.let { key ->
            model.catalog.objects.find { it.key == key }?.let {
                comparing = CompareRequest(CompareSubject.from(it))
            }
            onCompareOpened()
        }
    }

    LaunchedEffect(openEditorKey) {
        openEditorKey?.let { key ->
            model.selectCatalogObject(key)
            focusEditor = true
        }
    }

    if (focusEditor) {
        val edited = model.catalog.objects.find { it.key == model.selectedCatalogKey }
        Column(modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                CompactOutlinedButton(
                    onClick = {
                        if (onEditorBack == null) focusEditor = false else onEditorBack()
                    },
                    modifier = Modifier.testTag("library-editor-back"),
                ) { Text("← ${editorBackLabel ?: strings.navLibrary}") }
                Text(edited?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                edited?.let { VersionPill("v${it.version}") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Same rule as the catalog and Places: the edited object keeps a readable width and
                // the note beside it shrinks, then goes — where a rule is installed can also be read
                // from the catalog, whereas this is the only place its text is edited.
                val note = fitPanes(available = maxWidth, leading = 0.dp, trailing = FOCUS_NOTE_WIDTH).trailing
                Row(Modifier.fillMaxSize()) {
                    EditorPane(
                        model = model,
                        onDeleteBlock = { dialog = LibraryDialog.ConfirmDeleteBlock(it) },
                        onDeleteGroup = { dialog = LibraryDialog.ConfirmDeleteGroup(it) },
                        onDeleteProfile = { dialog = LibraryDialog.ConfirmDeleteProfile(it) },
                        onSplitBlock = { splitting = it },
                        onCompare = { comparing = CompareRequest(it) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        translation = translation,
                        onBlockSaved = onBlockSaved,
                        onSkillSaved = onSkillSaved,
                    )
                    edited?.takeIf { !model.usage.isEmpty }?.let { object_ ->
                        note?.let { width ->
                            VerticalDivider(color = MaterialTheme.colorScheme.outline)
                            InstallColumn(
                                item = object_,
                                sites = model.usage.sitesOf(object_.key),
                                onUninstall = { site ->
                                    dialog = LibraryDialog.ConfirmUninstall(
                                        key = object_.key,
                                        objectName = object_.name,
                                        placeId = site.placeId,
                                        placeName = site.placeName,
                                    )
                                },
                                modifier = Modifier.width(width).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    } else {
        LibraryCatalogScreen(
            model = model,
            onNew = { kind ->
                model.prepareCreate(kind)
                dialog = when (kind) {
                    LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MCP -> LibraryDialog.NewBlock
                    LibraryObjectKind.SKILL -> LibraryDialog.NewSkill
                    LibraryObjectKind.GROUP -> LibraryDialog.NewGroup
                    LibraryObjectKind.PROFILE -> LibraryDialog.NewProfile
                }
            },
            onImportSkills = { dialog = LibraryDialog.ImportSkills() },
            onEdit = { key ->
                model.selectCatalogObject(key)
                focusEditor = true
            },
            onDelete = { key ->
                when (key.kind) {
                    LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MCP -> model.blocks.find { it.id == key.id }
                        ?.let { dialog = LibraryDialog.ConfirmDeleteBlock(it) }
                    LibraryObjectKind.SKILL -> model.skills.find { it.id == key.id }
                        ?.let { dialog = LibraryDialog.ConfirmDeleteSkill(it) }
                    LibraryObjectKind.GROUP -> model.groups.find { it.id == key.id }
                        ?.let { dialog = LibraryDialog.ConfirmDeleteGroup(it) }
                    LibraryObjectKind.PROFILE -> model.profiles.find { it.id == key.id }
                        ?.let { dialog = LibraryDialog.ConfirmDeleteProfile(it) }
                }
            },
            onCompare = { key ->
                model.catalog.objects.find { it.key == key }?.let {
                    comparing = CompareRequest(CompareSubject.from(it))
                }
            },
            onCompareUpstream = { forkKey, upstreamKey ->
                model.catalog.objects.find { it.key == forkKey }?.let { fork ->
                    comparing = CompareRequest(CompareSubject.from(fork), upstreamKey)
                }
            },
            onOpenPlace = onOpenPlace,
            onInstallMarked = { dialog = LibraryDialog.InstallMarked },
            onAddMarkedToGroup = { dialog = LibraryDialog.AddMarkedToGroup },
            onExportMarked = { dialog = model.exportMarkedDialog(strings) },
            onCombineMarkedRules = {
                model.createCombinedRule(strings.libCombinedRuleName)?.let { focusEditor = true }
            },
            groupsExpandedByDefault = groupsExpandedByDefault,
            sourceUpdateIds = sourceUpdateIds,
            sourceUpdatesChecked = sourceUpdatesChecked,
            modifier = modifier,
        )
    }

    LibraryDialogHost(model, dialog, onDismiss = { dialog = null }, onReplace = { dialog = it })

    comparing?.let { request ->
        ComparePane(
            model = model,
            source = request.source,
            initialCandidateKey = request.initialCandidateKey,
            onDismiss = { comparing = null },
        )
    }

    splitting?.let { source ->
        SplitDialog(
            title = strings.splitDialogTitleLibrary(source.name.ifBlank { source.id }),
            sourceText = source.content,
            existingBlockIds = model.blocks.map { it.id }.toSet(),
            nameFormat = model.nameFormat,
            defaultDescription = source.description,
            onDismiss = { splitting = null },
            onConfirm = { result ->
                model.splitBlock(source, result)
                splitting = null
            },
        )
    }

    model.failure?.let { FailureDialog(it, onDismiss = model::clearFailure) }
}

private data class CompareRequest(
    val source: CompareSubject,
    val initialCandidateKey: LibraryObjectKey? = null,
)

private val FOCUS_NOTE_WIDTH = 300.dp

/**
 * The editor's right column: what saving costs, then the places this object actually sits in. A count
 * alone ("installed in 7 places") leaves the user to go hunting; the named list is what they can act
 * on, so each row carries its own removal.
 */
@Composable
private fun InstallColumn(
    item: LibraryObject,
    sites: List<LibraryInstallSite>,
    onUninstall: (LibraryInstallSite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(14.dp),
    ) {
        item { SaveImpact(item, sites.size) }
        item {
            Text(
                strings.libInstalledIn(sites.size).uppercase(),
                style = SectionHeaderStyle,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (sites.isEmpty()) {
            item {
                Text(
                    strings.libNotInstalled,
                    style = MaterialTheme.typography.bodySmall,
                    color = RuleblendTheme.extraColors.faint,
                )
            }
        } else {
            items(sites, key = { "place:${it.placeId}" }) { site ->
                InstallSiteRow(site) { onUninstall(site) }
            }
        }
    }
}

/**
 * What saving costs: a new library version marks every existing install update-available, and at
 * 30 places that is the difference between a typo fix and a fleet-wide chore.
 */
@Composable
private fun SaveImpact(item: LibraryObject, installs: Int, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val next = "v${item.version.toIntOrNull()?.plus(1) ?: item.version}"
    Text(
        if (installs == 0) strings.libSaveImpactUnused(next) else strings.libSaveImpact(installs, next),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** One place holding this object: its name, how the copy there compares, and the way out of it. */
@Composable
private fun InstallSiteRow(site: LibraryInstallSite, onUninstall: () -> Unit) {
    val strings = LocalStrings.current
    val (badge, label) = when (site.install.status) {
        InstallStatus.SYNCED -> SyncBadge.SYNCED to strings.libStatusSynced
        InstallStatus.UPDATE_AVAILABLE -> SyncBadge.UPDATE to strings.libStatusUpdate
        InstallStatus.MODIFIED -> SyncBadge.MODIFIED to strings.libStatusModified
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            site.placeName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusBadge(badge, label)
        RuleblendTooltip(strings.libUninstallHint) {
            CompactGhostButton(onClick = onUninstall) { Text("✕") }
        }
    }
}
/** Picks a destination and writes the archive; `null` when the user cancels the picker. */
fun LibraryModel.exportDialog(strings: Strings): LibraryDialog? {
    val zip = pickZipToSave() ?: return null
    return runCatching { export(zip) }.fold(
        onSuccess = { LibraryDialog.Message(strings.libExportedTitle, strings.libExportedText(zip.toString())) },
        onFailure = { LibraryDialog.Message(strings.libExportFailedTitle, it.message ?: it.toString()) },
    )
}

/**
 * Writes the marked objects to a chosen zip; `null` when the user cancels the picker. The selection
 * survives a cancelled picker — dropping it would punish a misclick with re-ticking rows.
 */
fun LibraryModel.exportMarkedDialog(strings: Strings): LibraryDialog? {
    val zip = pickZipToSave() ?: return null
    return runCatching { exportMarked(zip) }.fold(
        onSuccess = {
            clearMarks()
            LibraryDialog.Message(strings.libExportedTitle, strings.libExportedText(zip.toString()))
        },
        onFailure = { LibraryDialog.Message(strings.libExportFailedTitle, it.message ?: it.toString()) },
    )
}

/** Reads an archive and shows what it would change. A malformed zip reports instead of crashing. */
fun LibraryModel.importDialog(strings: Strings): LibraryDialog? {
    val zip = pickZipToOpen() ?: return null
    return runCatching { planImport(zip) }.fold(
        onSuccess = { plan ->
            if (plan.isEmpty) LibraryDialog.Message(strings.libNothingToImportTitle, strings.libNothingToImportText)
            else LibraryDialog.Import(plan)
        },
        onFailure = {
            LibraryDialog.Message(strings.libImportFailedTitle, strings.libImportFailedText(zip.toString(), it.message ?: it.toString()))
        },
    )
}
