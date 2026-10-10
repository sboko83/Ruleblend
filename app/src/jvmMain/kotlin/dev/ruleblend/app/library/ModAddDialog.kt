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
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.util.pickDirectory
import dev.ruleblend.core.storage.GitRepositoryImportPlan
import dev.ruleblend.core.storage.ModImportCandidate
import java.nio.file.Path
import kotlinx.coroutines.launch

/** Discovery captures a detached preview; the second explicit click accepts selected whole packages. */
@Composable
internal fun ModAddDialog(model: ModUiModel, onDismiss: () -> Unit, existingId: String? = null, initialFolder: String = "") {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var git by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(initialFolder) }
    var plan by remember { mutableStateOf<GitRepositoryImportPlan?>(null) }
    var candidates by remember { mutableStateOf<List<ModImportCandidate>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = candidates
    RuleblendDialog(onDismiss = { if (!running && !model.busy) onDismiss() }, title = if (existingId == null) strings.modAdd else strings.modReplaceLocal,
        confirmLabel = if (preview == null) strings.modPreview else strings.libSkillImportSelected,
        confirmEnabled = !running && !model.busy && if (preview == null) source.isNotBlank() else selected.isNotEmpty(),
        onConfirm = {
            running = true
            scope.launch {
                try {
                    if (preview == null) {
                        val fetched = if (git) model.fetch(source) else null
                        val found = fetched?.mods ?: listOf(model.preview(Path.of(source)))
                        plan = fetched; candidates = found
                        selected = found.filter { it.snapshot != null }.filterNot { candidate ->
                            git && model.mods.any { it.source?.let { provenance ->
                                provenance.repository == fetched?.repository && provenance.path == candidate.path
                            } == true }
                        }.map { it.path }.toSet()
                        if (found.isEmpty()) error = strings.modAbsent
                    } else {
                        val accepted = plan?.let { model.importGit(it, selected) }
                            ?: model.import(preview.single { it.path in selected }, existingId)
                        if (accepted) onDismiss()
                    }
                } catch (failure: Exception) { error = failure.message ?: failure.toString() }
                finally { running = false }
            }
        }, dismissLabel = strings.actionCancel, maxWidth = 680.dp) {
        if (preview == null) {
            if (existingId == null && initialFolder.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactOutlinedButton(onClick = { git = false }, enabled = git && !running) { Text(strings.modLocal) }
                CompactOutlinedButton(onClick = { git = true }, enabled = !git && !running) { Text(strings.modGit) }
            }
            RuleblendOutlinedTextField(source, { source = it; error = null }, singleLine = true, enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("mod-import-source"))
            if (!git) CompactOutlinedButton(onClick = { pickDirectory(strings.modFolder)?.let { source = it.toString() } }, enabled = !running,
                modifier = Modifier.testTag("mod-import-pick")) { Text(strings.modFolder) }
        } else LazyColumn(Modifier.heightIn(max = 400.dp).testTag("mod-import-preview"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(preview, key = { it.path }) { candidate ->
                Column {
                    val snapshot = candidate.snapshot
                    Row {
                        SquareCheckbox(candidate.path in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + candidate.path else selected - candidate.path
                        }, enabled = snapshot != null && !running && !model.busy, modifier = Modifier.testTag("mod-import-select:${candidate.path}"))
                        Text(snapshot?.mod?.name ?: candidate.path)
                    }
                    Text(modInventoryText(candidate), style = MaterialTheme.typography.bodySmall)
                }
            }
            plan?.errors?.forEach { failure -> item { Text("${failure.path}: ${failure.message}", color = MaterialTheme.colorScheme.error) } }
        }
        Text(strings.modImportWarning, style = MaterialTheme.typography.bodySmall)
        (error ?: model.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mod-import-error")) }
    }
}
