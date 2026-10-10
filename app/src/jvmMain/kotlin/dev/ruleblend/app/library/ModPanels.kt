package dev.ruleblend.app.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.core.integration.*
import dev.ruleblend.core.storage.ModImportCandidate
import dev.ruleblend.core.usecase.ModLibraryRemovalChoice
import dev.ruleblend.core.usecase.ModLibraryRemovalPreview
import kotlinx.coroutines.launch

internal fun modInventoryText(candidate: ModImportCandidate): String = buildString {
    candidate.snapshot?.mod?.let { appendLine("${it.manifestName} ${it.version.orEmpty()}") }
    appendLine(candidate.components.sorted().joinToString(", "))
    candidate.files.forEach { appendLine(it) }
    candidate.error?.let { appendLine(it) }
}

internal fun ModRuntimeStatus.label(strings: Strings): String = when (state) {
    ModRuntimeState.ABSENT -> strings.modAbsent
    ModRuntimeState.OWNED -> if (!installed) strings.modAbsent else if (enabled) strings.modEnabled else strings.modDisabled
    ModRuntimeState.FOREIGN -> strings.modForeign
    ModRuntimeState.DRIFT -> strings.modDrift
    ModRuntimeState.PENDING -> strings.modPending
    ModRuntimeState.UNSUPPORTED -> strings.modUnavailable
}

/** Inspector and Global use the same explicit lifecycle actions, including orphaned installations. */
@Composable
internal fun ModPanel(model: ModUiModel, id: String, modifier: Modifier = Modifier, refreshOnOpen: Boolean = true) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val mod = model.mods.find { it.id == id }
    val status = model.statuses[id]
    val compatible = model.packages[id]?.error == null
    var removing by remember(id) { mutableStateOf<ModRemovalPreview?>(null) }
    var deleting by remember(id) { mutableStateOf<ModLibraryRemovalPreview?>(null) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    var replacing by remember(id) { mutableStateOf(false) }
    var installing by remember(id) { mutableStateOf(false) }
    var inventoryOpen by remember(id) { mutableStateOf(false) }
    if (refreshOnOpen) LaunchedEffect(model, id) { model.refresh() }
    Column(modifier.fillMaxWidth().testTag("mod-panel:$id"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mod?.name ?: status?.pluginId ?: id, style = MaterialTheme.typography.titleSmall)
        Text("Claude Code · user", style = MaterialTheme.typography.labelMedium)
        mod?.let {
            Text("${it.manifestName} · ${it.version.orEmpty()}")
            Text("${strings.modLibraryRevision}: ${model.fingerprints[id]?.take(12).orEmpty()}", Modifier.testTag("mod-library-revision:$id"))
            it.source?.let { source ->
                Text("${source.repository}\n${source.path} @ ${source.revision.take(12)}", style = MaterialTheme.typography.bodySmall)
            }
            model.packages[id]?.let { candidate ->
                Text("${strings.modComponents}: ${candidate.components.sorted().joinToString(", ")}")
                candidate.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mod-compatibility:$id")) }
                CompactOutlinedButton(onClick = { inventoryOpen = true }, modifier = Modifier.testTag("mod-files:$id")) {
                    Text("${strings.modFiles} · ${candidate.files.size}")
                }
            }
        }
        Text(status?.label(strings) ?: strings.modUnavailable, Modifier.testTag("mod-status:$id"))
        if (mod == null) Text(strings.modOrphan)
        if (status?.installed == true) Text("${strings.modInstalledRevision}: ${status.fingerprint?.take(12).orEmpty()}",
            Modifier.testTag("mod-installed-revision:$id"))
        if (status?.reloadRequired == true) Text(strings.modReload, Modifier.testTag("mod-reload:$id"))
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mod-error:$id")) }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (mod != null && (status?.state == ModRuntimeState.ABSENT || status?.state == ModRuntimeState.OWNED && !status.installed)) {
                CompactOutlinedButton(onClick = { installing = true }, enabled = !model.busy && compatible, modifier = Modifier.testTag("mod-install:$id")) {
                    Text(strings.modInstall)
                }
            }
            if (status?.state == ModRuntimeState.OWNED && status.installed) {
                if (model.updateAvailable(id)) CompactOutlinedButton(onClick = { scope.launch { model.update(id) } },
                    enabled = !model.busy && compatible, modifier = Modifier.testTag("mod-update:$id")) { Text(strings.modUpdate) }
                CompactOutlinedButton(onClick = { scope.launch { model.toggle(id, !status.enabled) } },
                    enabled = !model.busy, modifier = Modifier.testTag("mod-toggle:$id")) {
                    Text(if (status.enabled) strings.modDisable else strings.modEnable)
                }
                CompactOutlinedButton(onClick = { scope.launch {
                    runCatching { model.previewUninstall(id) }.onSuccess { removing = it }.onFailure { failure = it.message }
                } }, enabled = !model.busy, modifier = Modifier.testTag("mod-uninstall:$id")) { Text(strings.modUninstall) }
            }
            if (status?.state == ModRuntimeState.PENDING) CompactOutlinedButton(onClick = { scope.launch { model.recover() } },
                enabled = !model.busy, modifier = Modifier.testTag("mod-recover:$id")) { Text(strings.modRecover) }
            if (mod != null) {
                if (mod.source != null) CompactOutlinedButton(onClick = { scope.launch { model.updateSource(id) } },
                    enabled = !model.busy, modifier = Modifier.testTag("mod-source-update:$id")) { Text(strings.modSourceUpdate) }
                else CompactOutlinedButton(onClick = { replacing = true }, enabled = !model.busy,
                    modifier = Modifier.testTag("mod-replace:$id")) { Text(strings.modReplaceLocal) }
                CompactOutlinedButton(onClick = { scope.launch {
                    runCatching { model.previewDelete(id) }.onSuccess { deleting = it }.onFailure { failure = it.message }
                } }, enabled = !model.busy, modifier = Modifier.testTag("mod-delete:$id")) { Text(strings.modDelete) }
            }
            CompactOutlinedButton(onClick = { scope.launch { model.refresh() } }, enabled = !model.busy,
                modifier = Modifier.testTag("mod-refresh:$id")) { Text(strings.modRefresh) }
        }
    }
    if (installing) RuleblendDialog(onDismiss = { if (!model.busy) installing = false }, title = strings.modInstall,
        confirmLabel = strings.modInstall, confirmEnabled = !model.busy,
        onConfirm = { scope.launch { if (model.install(id)) installing = false } }, dismissLabel = strings.actionCancel) {
        Text(strings.modRunWarning)
        model.packages[id]?.let { candidate ->
            Text(candidate.components.sorted().joinToString(", "))
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(candidate.files) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    removing?.let { preview ->
        RuleblendDialog(onDismiss = { if (!model.busy) removing = null }, title = strings.modUninstall,
            confirmLabel = strings.modUninstall, confirmEnabled = !model.busy,
            onConfirm = { scope.launch { if (model.uninstall(id, preview)) removing = null } }, dismissLabel = strings.actionCancel) {
            Text(strings.modRemovalConsequences)
            preview.consequences.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    deleting?.let { preview ->
        var choice by remember(preview) { mutableStateOf(ModLibraryRemovalChoice.KEEP_INSTALLATION) }
        RuleblendDialog(onDismiss = { if (!model.busy) deleting = null }, title = strings.modDelete,
            confirmLabel = strings.modDelete, confirmEnabled = !model.busy,
            onConfirm = { scope.launch { if (model.delete(id, preview, choice)) deleting = null } }, dismissLabel = strings.actionCancel) {
            Text(strings.modDeleteHint)
            Text(preview.runtime.label(strings))
            if (preview.uninstall != null) {
                Text(strings.modRemovalConsequences)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactOutlinedButton(onClick = { choice = ModLibraryRemovalChoice.KEEP_INSTALLATION },
                        enabled = choice != ModLibraryRemovalChoice.KEEP_INSTALLATION,
                        modifier = Modifier.testTag("mod-delete-keep:$id")) { Text(strings.modKeepInstallation) }
                    CompactOutlinedButton(onClick = { choice = ModLibraryRemovalChoice.UNINSTALL },
                        enabled = choice != ModLibraryRemovalChoice.UNINSTALL,
                        modifier = Modifier.testTag("mod-delete-uninstall:$id")) { Text(strings.modDeleteAndUninstall) }
                }
            }
            Text(if (choice == ModLibraryRemovalChoice.UNINSTALL) strings.modDeleteAndUninstall else strings.modKeepInstallation)
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (replacing) ModAddDialog(model, onDismiss = { replacing = false }, existingId = id)
    if (inventoryOpen) RuleblendDialog(onDismiss = { inventoryOpen = false }, title = strings.modFiles,
        confirmLabel = strings.actionClose, onConfirm = { inventoryOpen = false }) {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(model.packages[id]?.files.orEmpty()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun ModGlobalPanel(model: ModUiModel, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.refresh() }
    LazyColumn(modifier.fillMaxSize().padding(16.dp).testTag("place-mods"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactOutlinedButton(onClick = { adding = true }, enabled = !model.busy, modifier = Modifier.testTag("global-add-mod")) { Text(strings.modAdd) }
                CompactOutlinedButton(onClick = { scope.launch { model.refresh() } }, enabled = !model.busy) { Text(strings.modRefresh) }
                CompactOutlinedButton(onClick = { scope.launch { model.recover() } }, enabled = !model.busy,
                    modifier = Modifier.testTag("global-recover-mods")) { Text(strings.modRecover) }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(model.entries.filter { it.libraryId != null }.distinctBy { it.libraryId }, key = { "owned:${it.libraryId}" }) { entry ->
            ModPanel(model, requireNotNull(entry.libraryId), refreshOnOpen = false)
        }
        items(model.entries.filter { it.libraryId == null }, key = { "foreign:${it.scope}:${it.pluginId}" }) { entry ->
            Column(Modifier.testTag("mod-external:${entry.pluginId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.pluginId, style = MaterialTheme.typography.titleSmall)
                Text(if (entry.builtIn) strings.modBuiltin else strings.modForeign)
                Text("${entry.scope} · ${if (entry.enabled) strings.modEnabled else strings.modDisabled}")
                if (!entry.builtIn && entry.installPath != null) CompactOutlinedButton(
                    onClick = { importing = entry.installPath }, enabled = !model.busy,
                    modifier = Modifier.testTag("mod-import-installed:${entry.pluginId}")) { Text(strings.modImportInstalled) }
            }
        }
        if (model.entries.isEmpty()) item { Text(strings.modAbsent) }
    }
    if (adding) ModAddDialog(model, onDismiss = { adding = false })
    importing?.let { path -> ModAddDialog(model, onDismiss = { importing = null }, initialFolder = path) }
}

/** The Mods tab selects this palette; other tabs retain their normal sections. */
@Composable
internal fun ModPalette(model: ModUiModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val strings = LocalStrings.current
    LazyColumn(modifier.fillMaxSize().padding(12.dp).testTag("place-mod-palette"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { dev.ruleblend.app.theme.RuleblendOutlinedTextField(value = query, onValueChange = { query = it },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("mod-palette-search")) }
        item { Text(strings.libMods, style = MaterialTheme.typography.titleSmall) }
        items(model.mods.filter { query.isBlank() || "${it.name} ${it.description}".contains(query, ignoreCase = true) }, key = { it.id }) { mod ->
            ModPanel(model, mod.id, refreshOnOpen = false)
        }
    }
}
