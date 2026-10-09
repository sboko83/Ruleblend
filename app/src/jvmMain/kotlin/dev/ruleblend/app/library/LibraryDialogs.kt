package dev.ruleblend.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendConfirmDialog
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.exchange.ChangeKind
import dev.ruleblend.core.exchange.ImportPlan
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.GitSkillImportPlan
import kotlinx.coroutines.launch

sealed interface LibraryDialog {
    data object NewBlock : LibraryDialog

    data object NewGroup : LibraryDialog

    data object NewProfile : LibraryDialog

    /** Create a rule and add it to the currently edited group in one step. */
    data object CreateBlockInGroup : LibraryDialog

    data class ImportSkills(val repository: String = "") : LibraryDialog

    data object NewSkill : LibraryDialog

    data class ConfirmDeleteBlock(val block: Block) : LibraryDialog

    data class ConfirmDeleteGroup(val group: Group) : LibraryDialog

    data class ConfirmDeleteProfile(val profile: Profile) : LibraryDialog

    data class ConfirmDeleteSkill(val skill: Skill) : LibraryDialog

    data class Import(val plan: ImportPlan) : LibraryDialog

    /** Pick the places the marked objects are written into. */
    data object InstallMarked : LibraryDialog

    /** Pick the group the marked objects join. */
    data object AddMarkedToGroup : LibraryDialog

    /** Take one object out of one place, from the editor's "installed in" list. */
    data class ConfirmUninstall(
        val key: LibraryObjectKey,
        val objectName: String,
        val placeId: String,
        val placeName: String,
    ) : LibraryDialog

    data class Message(val title: String, val text: String) : LibraryDialog
}

/**
 * [onReplace] lets a dialog hand over to its own result dialog — a bulk action reports what it wrote
 * before the selection is dropped. Hosts without bulk actions may leave it at the default.
 */
@Composable
fun LibraryDialogHost(
    model: LibraryModel,
    dialog: LibraryDialog?,
    onDismiss: () -> Unit,
    onReplace: (LibraryDialog) -> Unit = { onDismiss() },
) {
    val strings = LocalStrings.current
    // The diff window is opened from a revision row rather than from a menu, so it is not one of the
    // [LibraryDialog] cases — but it is a dialog of this screen and belongs in the same host.
    if (model.openDiff != null) {
        val title = model.catalog.objects.find { it.key == model.selectedCatalogKey }?.name.orEmpty()
        RevisionDiffDialog(model, title, onDismiss = model::closeDiff)
    }
    when (dialog) {
        null -> Unit

        LibraryDialog.NewBlock -> {
            val type = (model.selection as? Selection.Type)?.type ?: BlockType.RULE
            val title = when (type) {
                BlockType.MCP -> strings.libNewMcpTitle
                BlockType.SUBAGENT -> strings.libNewSubagentTitle
                BlockType.RULE -> strings.libNewRuleTitle
            }
            NameDialog(model, title, onDismiss) { name -> model.createBlock(name, type) }
        }

        LibraryDialog.NewGroup -> NameDialog(model, strings.libNewGroupTitle, onDismiss) { model.createGroup(it) }

        LibraryDialog.NewProfile -> NameDialog(model, strings.libNewProfileTitle, onDismiss) { model.createProfile(it) }

        LibraryDialog.CreateBlockInGroup -> NameDialog(model, strings.libNewRuleInGroupTitle, onDismiss) { model.createBlockInGroup(it) }

        is LibraryDialog.ImportSkills -> ImportSkillsDialog(model, dialog.repository, onDismiss)

        LibraryDialog.NewSkill -> NewSkillDialog(model, onDismiss)

        is LibraryDialog.ConfirmDeleteBlock -> ConfirmDialog(
            text = strings.libDeleteRuleConfirm(dialog.block.name),
            onDismiss = onDismiss,
            onConfirm = { model.deleteBlock(dialog.block.id) },
        )

        is LibraryDialog.ConfirmDeleteGroup -> ConfirmDialog(
            text = strings.libDeleteGroupConfirm(dialog.group.name),
            onDismiss = onDismiss,
            onConfirm = { model.deleteGroup(dialog.group.id) },
        )

        is LibraryDialog.ConfirmDeleteProfile -> ConfirmDialog(
            text = strings.libDeleteProfileConfirm(dialog.profile.name),
            onDismiss = onDismiss,
            onConfirm = { model.deleteProfile(dialog.profile.id) },
        )

        is LibraryDialog.ConfirmDeleteSkill -> ConfirmDialog(
            text = strings.libDeleteSkillConfirm(dialog.skill.name),
            onDismiss = onDismiss,
            onConfirm = { model.deleteSkill(dialog.skill.id) },
        )

        is LibraryDialog.Import -> ImportDialog(model, dialog.plan, onDismiss)

        LibraryDialog.InstallMarked -> InstallMarkedDialog(model, onDismiss, onReplace)

        LibraryDialog.AddMarkedToGroup -> AddMarkedToGroupDialog(model, onDismiss, onReplace)

        is LibraryDialog.ConfirmUninstall -> UninstallDialog(model, dialog, onDismiss, onReplace)

        is LibraryDialog.Message -> RuleblendDialog(
            onDismiss = onDismiss,
            title = dialog.title,
            text = dialog.text,
            confirmLabel = strings.actionOk,
            onConfirm = onDismiss,
        )
    }
}

/**
 * Bulk install: the user picks places, the model writes through the Place services. The dialog stays
 * open while writing — closing it early would hide a report the user has to see.
 */
@Composable
private fun InstallMarkedDialog(model: LibraryModel, onDismiss: () -> Unit, onReplace: (LibraryDialog) -> Unit) {
    val strings = LocalStrings.current
    val places = remember { model.installPlaces() }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val count = model.marked.size
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.libInstallIntoTitle,
        text = if (places.isEmpty()) strings.libInstallNoPlaces else strings.libInstallIntoText(count),
        confirmLabel = strings.libInstallConfirm,
        confirmEnabled = chosen.isNotEmpty() && !running,
        dismissLabel = strings.actionCancel,
        onConfirm = {
            running = true
            scope.launch {
                val report = model.installMarked(chosen)
                model.clearMarks()
                onReplace(LibraryDialog.Message(strings.libInstallReportTitle, report.summary(strings)))
            }
        },
    ) {
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(places, key = { it.id }) { place ->
                ImportRow(
                    name = place.name,
                    detail = if (place.kind == LibraryPlaceKind.AGENT) strings.intAgents else strings.intProjects,
                    checked = place.id in chosen,
                    onCheckedChange = { on -> chosen = if (on) chosen + place.id else chosen - place.id },
                    checkboxTag = "library-install-place:${place.id}",
                )
            }
        }
    }
}

/**
 * Removing one object from one place. Confirmed rather than immediate: it writes into a user file,
 * and the row it is triggered from is a one-click target next to the place name.
 */
@Composable
private fun UninstallDialog(
    model: LibraryModel,
    dialog: LibraryDialog.ConfirmUninstall,
    onDismiss: () -> Unit,
    onReplace: (LibraryDialog) -> Unit,
) {
    val strings = LocalStrings.current
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.libUninstallTitle,
        text = strings.libUninstallText(dialog.objectName, dialog.placeName),
        confirmLabel = strings.libUninstallConfirm,
        confirmEnabled = !running,
        destructive = true,
        dismissLabel = strings.actionCancel,
        onConfirm = {
            running = true
            scope.launch {
                val report = model.uninstallFrom(dialog.key, dialog.placeId)
                onReplace(LibraryDialog.Message(strings.libUninstallReportTitle, report.summary(strings, removal = true)))
            }
        },
    )
}

@Composable
private fun AddMarkedToGroupDialog(model: LibraryModel, onDismiss: () -> Unit, onReplace: (LibraryDialog) -> Unit) {
    val strings = LocalStrings.current
    val groups = model.groups.filter { it.id != ALL_GROUP_ID }
    var chosen by remember { mutableStateOf<String?>(null) }
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.libAddToGroupTitle,
        text = if (groups.isEmpty()) strings.libNoGroups else strings.libAddToGroupText(model.marked.size),
        confirmLabel = strings.libAddConfirm,
        confirmEnabled = chosen != null,
        dismissLabel = strings.actionCancel,
        onConfirm = {
            val groupId = chosen ?: return@RuleblendDialog
            val name = groups.find { it.id == groupId }?.name ?: groupId
            val added = model.addMarkedToGroup(groupId)
            model.clearMarks()
            onReplace(
                LibraryDialog.Message(
                    strings.libAddedToGroupTitle,
                    if (added == 0) strings.libAddedNothingText else strings.libAddedToGroupText(added, name),
                ),
            )
        },
    ) {
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(groups, key = { it.id }) { group ->
                ImportRow(
                    name = group.name,
                    kind = LibraryObjectKind.GROUP,
                    detail = strings.libMembersCount(group.blockIds.size + group.skillIds.size),
                    checked = chosen == group.id,
                    onCheckedChange = { on -> chosen = if (on) group.id else null },
                )
            }
        }
    }
}

@Composable
internal fun ImportSkillsDialog(model: LibraryModel, initialRepository: String, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var repository by remember(initialRepository) { mutableStateOf(initialRepository) }
    var plan by remember { mutableStateOf<GitSkillImportPlan?>(null) }
    var selected by remember { mutableStateOf(GitImportSelection()) }
    var preview by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val currentPlan = plan
    RuleblendDialog(
        onDismiss = { if (!loading) onDismiss() },
        title = strings.libSkillImportTitle,
        confirmLabel = if (currentPlan == null) strings.libSkillDiscover else strings.libSkillImportSelected,
        confirmEnabled = !loading && if (currentPlan == null) repository.isNotBlank() else !selected.isEmpty,
        onConfirm = {
            loading = true
            coroutineScope.launch {
                try {
                    if (currentPlan == null) {
                        runCatching { model.fetchGitSkills(repository) }
                            .onSuccess { fetched ->
                                plan = fetched
                                selected = GitImportSelection.initial(fetched, model.skills, model.blocks)
                                error = null
                            }
                            .onFailure { error = it.message ?: it.toString() }
                    } else if (model.applyGitImport(currentPlan, selected)) {
                        onDismiss()
                    }
                } finally { loading = false }
            }
        },
        dismissLabel = strings.actionCancel,
    ) {
        if (currentPlan == null) {
            LabelledField(strings.libSkillRepository) {
                RuleblendOutlinedTextField(
                    value = repository,
                    onValueChange = { repository = it; error = null },
                    enabled = !loading,
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.fillMaxWidth().testTag("library-import-repository"),
                )
            }
            Text(strings.libSkillRepositoryHint, style = MaterialTheme.typography.labelSmall)
        } else {
            Text(strings.libGitFound(currentPlan.skills.size, currentPlan.subagents.size, currentPlan.errors.size),
                style = MaterialTheme.typography.bodySmall)
            Text("@ ${currentPlan.revision.take(12)}", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.heightIn(max = 400.dp).testTag("library-git-import-list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(currentPlan.skills, key = { "skill:${it.skill.source?.path}" }) { snapshot ->
                    val path = requireNotNull(snapshot.skill.source).path
                    Column {
                        ImportRow(snapshot.skill.name, path, path in selected.skills,
                            { checked -> if (!loading) selected = selected.copy(skills = if (checked) selected.skills + path else selected.skills - path) },
                            "library-git-import:$path", LibraryObjectKind.SKILL)
                        GitImportPreview(path, snapshot.skill.content, preview == "skill:$path") {
                            preview = if (preview == "skill:$path") null else "skill:$path"
                        }
                    }
                }
                items(currentPlan.subagents, key = { "subagent:${it.path}" }) { candidate ->
                    val path = candidate.path
                    val assistant = selected.assistants[path]
                    val draft = candidate.drafts[assistant] ?: candidate.drafts.values.first()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ImportRow(draft.name.orEmpty(), path, path in selected.subagents,
                            { checked -> if (!loading) selected = selected.toggleSubagent(path, checked) },
                            "library-git-import-subagent:$path", LibraryObjectKind.SUBAGENT)
                        if (candidate.requiresAssistantSelection) {
                            if (assistant == null) Text(strings.libGitChooseAssistant,
                                style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                candidate.drafts.keys.forEach { id ->
                                    CompactOutlinedButton(
                                        onClick = { selected = selected.chooseAssistant(path, id) },
                                        enabled = !loading && assistant != id,
                                        modifier = Modifier.testTag("library-git-assistant:$path:$id"),
                                    ) { Text(gitAssistantName(id)) }
                                }
                            }
                        } else Text(gitAssistantName(requireNotNull(assistant)), style = MaterialTheme.typography.labelSmall)
                        GitImportPreview(path, candidate.sourceText, preview == "subagent:$path") {
                            preview = if (preview == "subagent:$path") null else "subagent:$path"
                        }
                    }
                }
                items(currentPlan.errors, key = { "error:${it.path}" }) { failure ->
                    Column(Modifier.testTag("library-git-error:${failure.path}")) {
                        Text("${failure.path}: ${failure.message}", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        failure.sourceText?.let { text ->
                            GitImportPreview(failure.path, text, preview == "error:${failure.path}") {
                                preview = if (preview == "error:${failure.path}") null else "error:${failure.path}"
                            }
                        }
                    }
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun gitAssistantName(id: String): String = when (id) {
    "codex" -> "Codex"
    "claude-code" -> "Claude Code"
    "kimi-code" -> "Kimi Code"
    else -> id
}

@Composable
private fun GitImportPreview(path: String, text: String, expanded: Boolean, onToggle: () -> Unit) {
    CompactOutlinedButton(onClick = onToggle, modifier = Modifier.testTag("library-git-preview:$path")) {
        Text(LocalStrings.current.libGitPreview)
    }
    if (expanded) RuleblendOutlinedTextField(
        value = text, onValueChange = {}, readOnly = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag("library-git-preview-text:$path"),
    )
}

/**
 * Lets the user pick what to take from an archive. New and newer items start ticked; conflicts do
 * not — a conflict overwrites local work, so it needs a deliberate click.
 */
@Composable
private fun ImportDialog(model: LibraryModel, plan: ImportPlan, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var blockIds by remember(plan) {
        mutableStateOf(
            plan.actionableBlocks.filter { it.kind != ChangeKind.CONFLICT }.map { it.incoming.id }.toSet(),
        )
    }
    var groupIds by remember(plan) {
        mutableStateOf(
            plan.actionableGroups.filter { it.kind != ChangeKind.CONFLICT }.map { it.incoming.id }.toSet(),
        )
    }
    var skillIds by remember(plan) {
        mutableStateOf(
            plan.actionableSkills.filter { it.kind != ChangeKind.CONFLICT }.map { it.incoming.skill.id }.toSet(),
        )
    }
    var profileIds by remember(plan) {
        mutableStateOf(
            plan.actionableProfiles.filter { it.kind != ChangeKind.CONFLICT }.map { it.incoming.id }.toSet(),
        )
    }
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.libImportTitle,
        confirmLabel = strings.libImport,
        confirmEnabled = blockIds.isNotEmpty() || groupIds.isNotEmpty() || skillIds.isNotEmpty() || profileIds.isNotEmpty(),
        onConfirm = {
            model.applyImport(plan, blockIds, groupIds, skillIds, profileIds)
            onDismiss()
        },
        dismissLabel = strings.actionCancel,
    ) {
        LazyColumn(Modifier.heightIn(max = 320.dp).testTag("library-archive-import-list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (plan.actionableBlocks.isNotEmpty()) {
                item { SectionLabel(strings.libRulesLabel) }
                items(plan.actionableBlocks, key = { it.incoming.id }) { change ->
                    ImportRow(
                        name = change.incoming.name,
                        detail = change.detail(strings),
                        checked = change.incoming.id in blockIds,
                        onCheckedChange = { checked ->
                            blockIds =
                                if (checked) blockIds + change.incoming.id else blockIds - change.incoming.id
                        },
                        checkboxTag = "library-archive-import:BLOCK:${change.incoming.id}",
                        kind = change.incoming.type.objectKind(),
                    )
                }
            }
            if (plan.actionableGroups.isNotEmpty()) {
                item { SectionLabel(strings.libGroups) }
                items(plan.actionableGroups, key = { it.incoming.id }) { change ->
                    ImportRow(
                        name = change.incoming.name,
                        detail = change.detail(strings),
                        checked = change.incoming.id in groupIds,
                        onCheckedChange = { checked ->
                            groupIds =
                                if (checked) groupIds + change.incoming.id else groupIds - change.incoming.id
                        },
                        checkboxTag = "library-archive-import:GROUP:${change.incoming.id}",
                        kind = LibraryObjectKind.GROUP,
                    )
                }
            }
            if (plan.actionableSkills.isNotEmpty()) {
                item { SectionLabel(strings.libSkills) }
                items(plan.actionableSkills, key = { it.incoming.skill.id }) { change ->
                    ImportRow(
                        name = change.incoming.skill.name,
                        detail = change.kind.label(strings),
                        checked = change.incoming.skill.id in skillIds,
                        onCheckedChange = { checked ->
                            skillIds = if (checked) skillIds + change.incoming.skill.id else skillIds - change.incoming.skill.id
                        },
                        checkboxTag = "library-archive-import:SKILL:${change.incoming.skill.id}",
                        kind = LibraryObjectKind.SKILL,
                    )
                }
            }
            if (plan.actionableProfiles.isNotEmpty()) {
                item { SectionLabel(strings.libProfiles) }
                items(plan.actionableProfiles, key = { it.incoming.id }) { change ->
                    ImportRow(
                        name = change.incoming.name,
                        detail = change.detail(strings),
                        checked = change.incoming.id in profileIds,
                        onCheckedChange = { checked ->
                            profileIds = if (checked) profileIds + change.incoming.id else profileIds - change.incoming.id
                        },
                        checkboxTag = "library-archive-import:PROFILE:${change.incoming.id}",
                        kind = LibraryObjectKind.PROFILE,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ImportRow(
    name: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkboxTag: String? = null,
    kind: LibraryObjectKind? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SquareCheckbox(checked = checked, onCheckedChange = onCheckedChange,
            modifier = checkboxTag?.let { Modifier.testTag(it) } ?: Modifier)
        kind?.let { KindMark(it) }
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ChangeKind.label(strings: Strings): String = when (this) {
    ChangeKind.NEW -> strings.changeNew
    ChangeKind.UPDATE -> strings.changeUpdate
    ChangeKind.SAME -> strings.changeUnchanged
    ChangeKind.CONFLICT -> strings.changeConflict
}

private fun dev.ruleblend.core.exchange.BlockChange.detail(strings: Strings): String =
    versionDetail(strings, kind, local?.version, incoming.version)

/** A group is versioned library content too, so its row reads the same way a rule's does. */
private fun dev.ruleblend.core.exchange.GroupChange.detail(strings: Strings): String =
    versionDetail(strings, kind, local?.version, incoming.version)

private fun dev.ruleblend.core.exchange.ProfileChange.detail(strings: Strings): String =
    versionDetail(strings, kind, local?.version, incoming.version)

private fun versionDetail(strings: Strings, kind: ChangeKind, local: Int?, incoming: Int): String = when (kind) {
    ChangeKind.UPDATE -> "v$local → v$incoming"
    ChangeKind.CONFLICT -> "${strings.changeConflict} v$local → v$incoming"
    else -> kind.label(strings)
}

@Composable
private fun NameDialog(model: LibraryModel, title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val strings = LocalStrings.current
    val focusRequester = remember { FocusRequester() }
    RuleblendDialog(
        onDismiss = onDismiss,
        title = title,
        confirmLabel = strings.actionCreate,
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            onConfirm(name.trim())
            onDismiss()
        },
        dismissLabel = strings.actionCancel,
    ) {
        LabelledField(strings.fieldName) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleblendOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("library-create-name"),
                )
                CompactOutlinedButton(onClick = { name = model.previewName(name) }) { Text(strings.actionFormatName) }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun NewSkillDialog(model: LibraryModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val strings = LocalStrings.current
    val focusRequester = remember { FocusRequester() }
    val id = dev.ruleblend.core.model.nextId(name, model.skills.map { it.id })
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.libNewSkillTitle,
        confirmLabel = strings.actionCreate,
        confirmEnabled = name.isNotBlank() && description.isNotBlank() && dev.ruleblend.core.model.validSkillName(id),
        onConfirm = {
            model.createSkill(name.trim(), description.trim())
            onDismiss()
        },
        dismissLabel = strings.actionCancel,
    ) {
        LabelledField(strings.fieldName) {
            RuleblendOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("library-create-name"),
            )
        }
        Text("$id · ${strings.libSkillNameHint}", style = MaterialTheme.typography.labelSmall)
        LabelledField(strings.fieldDescription) {
            RuleblendOutlinedTextField(
                value = description,
                onValueChange = { description = it },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().testTag("library-create-description"),
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** Label above the box, matching the block editor's field style. */
@Composable
internal fun LabelledField(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = SectionHeaderStyle)
        field()
    }
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalStrings.current
    RuleblendConfirmDialog(
        text = text,
        confirmLabel = strings.actionDelete,
        dismissLabel = strings.actionCancel,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
