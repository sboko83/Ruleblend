package dev.ruleblend.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.home.HomeModel
import dev.ruleblend.app.library.LibraryDialog
import dev.ruleblend.app.library.LibraryModel
import dev.ruleblend.app.library.exportDialog
import dev.ruleblend.app.library.importDialog
import dev.ruleblend.app.library.pickZipToSave
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.Pill
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SegmentedControl
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.app.util.pickDirectory
import dev.ruleblend.app.util.revealInFileManager
import dev.ruleblend.core.config.SourceCheckMode
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.storage.RemoteSyncStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/** The name the Name format row is demonstrated on; a rule name, not a translated sentence. */
private const val NameSample = "iOS review checklist"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibrarySection(
    model: SettingsModel,
    library: LibraryModel,
    home: HomeModel?,
    narrow: Boolean,
    onDialog: (LibraryDialog?) -> Unit,
    operationScope: CoroutineScope,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    SettingsSectionBlock(SettingsSection.LIBRARY, strings.setSectionLibrary, null) {
        SettingsRow(strings.setLibraryPath, strings.setLibraryPathHint, narrow) {
            ControlRow {
                PathChip(model.libraryPath.abbreviateHome())
                CompactOutlinedButton(
                    onClick = {
                        pickDirectory(strings.setLibraryPathChange, model.libraryPath)?.let(model::changeLibraryPath)
                    },
                    modifier = Modifier.testTag("settings-library-change"),
                ) {
                    ButtonLabel(strings.setLibraryPathChange)
                }
                CompactGhostButton(onClick = { revealInFileManager(model.libraryPath) }) {
                    ButtonLabel(revealLabel(strings))
                }
            }
            LibraryStats(model, library)
            // The process opened one library at launch; a different configured path is a promise,
            // not a switch, so the row says so and offers the way back.
            if (model.libraryPathChanged) {
                ControlRow {
                    Note(strings.setLibraryPathRestartHint, RuleblendTheme.extraColors.warning)
                    Text(
                        strings.setUndo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(role = Role.Button, onClick = model::undoLibraryPathChange)
                            .testTag("settings-library-undo"),
                    )
                }
            }
        }
        RowDivider()
        SettingsRow(strings.setSourceCheck, strings.setSourceCheckHint, narrow) {
            SegmentedControl(
                options = listOf(strings.sourceCheckManual, strings.sourceCheckOnLaunch),
                selected = if (model.sourceCheck == SourceCheckMode.MANUAL) 0 else 1,
                onSelect = {
                    model.changeSourceCheck(if (it == 0) SourceCheckMode.MANUAL else SourceCheckMode.ON_LAUNCH)
                },
                testTagFor = { if (it == 0) "settings-source-check-manual" else "settings-source-check-on-launch" },
            )
        }
        RowDivider()
        SettingsRow(strings.setSources, strings.setSourcesHint, narrow) {
            val updates = home?.sourceUpdates
            if (updates == null || updates.sources.isEmpty()) {
                Note(strings.setSourcesEmpty)
            } else {
                ControlRow {
                    CompactOutlinedButton(
                        enabled = !updates.busy,
                        onClick = { scope.launch { home.checkSkillSources() } },
                        modifier = Modifier.testTag("settings-sources-check"),
                    ) {
                        ButtonLabel(
                            if (updates.checking) {
                                strings.homeSourceChecking(updates.progressDone, updates.progressTotal)
                            } else {
                                strings.homeSourceCheckNow
                            },
                        )
                    }
                    updates.failure?.let { Note(strings.homeSourceFailure(it), MaterialTheme.colorScheme.error) }
                }
                updates.sources.forEachIndexed { index, source ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth().testTag("settings-source-$index"),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(10.dp),
                        ) {
                            PathChip(source.repository, Modifier.fillMaxWidth())
                            ControlRow {
                                Note(strings.setSourceSkills(source.skillIds.size))
                                Note(strings.setSourceSubagents(source.subagentIds.size))
                                source.remoteHead?.let { Note(strings.setSourceHead(it.take(12))) }
                                Note(
                                    source.lastCheckedAt?.let(strings.homeSourceLastChecked)
                                        ?: strings.homeSourceNeverChecked,
                                )
                            }
                            CompactOutlinedButton(
                                onClick = { onDialog(LibraryDialog.ImportSkills(source.repository)) },
                                modifier = Modifier.testTag("settings-source-reopen-$index"),
                            ) {
                                ButtonLabel(strings.setSourceReopenImport)
                            }
                        }
                    }
                }
            }
        }
        RowDivider()
        SettingsRow(strings.setNameFormat, strings.setNameFormatHint, narrow) {
            ControlRow {
                SegmentedControl(
                    options = NameFormat.entries.map { it.label(strings) },
                    selected = NameFormat.entries.indexOf(model.nameFormat),
                    onSelect = { model.changeNameFormat(NameFormat.entries[it]) },
                    testTagFor = { "settings-name-format-${NameFormat.entries[it].name.lowercase()}" },
                )
                Text(
                    formatName(NameSample, model.nameFormat),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-name-sample"),
                )
            }
        }
        RowDivider()
        SettingsRow(strings.setRemoteGit, strings.remoteGitHint, narrow) {
            if (model.remoteEditing) {
                RemoteForm(model, onSave = { operationScope.launch { model.saveAndSyncRemote() } })
            } else RemoteSummary(model, onSync = { operationScope.launch { model.syncNow() } })
            model.remoteSyncFailure?.let { Note(it, MaterialTheme.colorScheme.error) }
        }
        if (model.unrelatedHistories) {
            UnrelatedHistoriesDialog(model, library, operationScope)
        }
        RowDivider()
        SettingsRow(strings.setLibraryData, strings.setLibraryDataHint, narrow) {
            ControlRow {
                CompactOutlinedButton(onClick = { onDialog(library.exportDialog(strings)) }) {
                    ButtonLabel(strings.libExport)
                }
                CompactOutlinedButton(onClick = { onDialog(library.importDialog(strings)) }) {
                    ButtonLabel(strings.libImport)
                }
                Note(strings.setImportNote)
            }
        }
    }
}

/** A foreign history is never merged or discarded by the failed Sync action itself. */
@Composable
private fun UnrelatedHistoriesDialog(model: SettingsModel, library: LibraryModel, scope: CoroutineScope) {
    val strings = LocalStrings.current
    RuleblendDialog(
        onDismiss = model::dismissUnrelatedHistories,
        title = strings.remoteGitUnrelatedTitle,
        text = strings.remoteGitUnrelatedText,
        confirmLabel = strings.remoteGitMergeAsImport,
        confirmEnabled = !model.remoteSyncing,
        onConfirm = { scope.launch { model.mergeUnrelatedHistories() } },
        alternativeLabel = strings.remoteGitReplaceAfterExport,
        onAlternative = {
            pickZipToSave("ruleblend-before-replace.zip")?.let { zip ->
                scope.launch { model.replaceUnrelatedLibrary { library.export(zip) } }
            }
        },
        dismissLabel = strings.actionCancel,
        destructive = false,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryStats(model: SettingsModel, library: LibraryModel) {
    val strings = LocalStrings.current
    val history = model.libraryHistory
    val stats = buildList {
        add(strings.setStatsRules(library.blocks.count { it.type == BlockType.RULE }))
        add(strings.setStatsSkills(library.skills.size))
        add(strings.setStatsMcp(library.blocks.count { it.type == BlockType.MCP }))
        add(strings.setStatsGroups(library.groups.count { it.id != ALL_GROUP_ID }))
        if (history != null) {
            add(strings.setStatsCommits(history.commits))
            history.lastCommitEpochMillis?.let { add(strings.setStatsLastChange(ago(it, strings))) }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("settings-library-stats"),
    ) {
        stats.forEach { stat ->
            Text(
                withLeadingNumberBold(stat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "204 rules" reads as a number first; the count is the content, the noun is the label. */
private fun withLeadingNumberBold(text: String): AnnotatedString {
    val digits = text.takeWhile(Char::isDigit)
    return buildAnnotatedString {
        if (digits.isEmpty()) {
            append(text)
            return@buildAnnotatedString
        }
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(digits) }
        append(text.removePrefix(digits))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RemoteForm(model: SettingsModel, onSave: () -> Unit) {
    val strings = LocalStrings.current
    ControlRow(alignment = Alignment.Bottom) {
        Column(Modifier.weight(1f, fill = false).widthIn(min = 240.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FieldLabel(strings.remoteGitUrl)
            RuleblendOutlinedTextField(
                value = model.remoteUrl,
                onValueChange = model::changeRemoteUrl,
                modifier = Modifier.fillMaxWidth().testTag("settings-remote-url"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                colors = ruleblendFieldColors(),
            )
        }
        Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FieldLabel(strings.remoteGitBranch)
            RuleblendOutlinedTextField(
                value = model.remoteBranch,
                onValueChange = model::changeRemoteBranch,
                modifier = Modifier.fillMaxWidth().testTag("settings-remote-branch"),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                colors = ruleblendFieldColors(),
            )
        }
    }
    ControlRow {
        SquareCheckbox(
            checked = model.remoteAutomatic,
            onCheckedChange = model::changeRemoteAutomatic,
            modifier = Modifier.testTag("settings-remote-automatic"),
        )
        Note(strings.remoteGitAutomatic)
        Spacer(Modifier.weight(1f))
        if (model.remoteConfigured) {
            CompactGhostButton(enabled = !model.remoteSyncing, onClick = model::cancelRemoteEdit) {
                ButtonLabel(strings.remoteGitCancel)
            }
        }
        CompactButton(
            enabled = model.remoteUrl.isNotBlank() && model.remoteBranch.isNotBlank() && !model.remoteSyncing,
            onClick = onSave,
            modifier = Modifier.testTag("settings-remote-save"),
        ) {
            ButtonLabel(if (model.remoteSyncing) strings.remoteGitSyncing else strings.remoteGitSaveAndSync)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RemoteSummary(model: SettingsModel, onSync: () -> Unit) {
    val strings = LocalStrings.current
    val extra = RuleblendTheme.extraColors
    val failed = model.remoteSyncStatus == RemoteSyncStatus.FAILED
    ControlRow {
        StatusDot(if (failed) extra.danger else extra.success)
        Text(
            model.remoteUrl,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("settings-remote-target"),
        )
        Pill(model.remoteBranch, MaterialTheme.colorScheme.background, RuleblendTheme.extraColors.faint)
        Note(remoteStatusLine(model, strings), if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ControlRow {
        CompactOutlinedButton(
            enabled = !model.remoteSyncing,
            onClick = onSync,
            modifier = Modifier.testTag("settings-remote-sync"),
        ) {
            ButtonLabel(if (model.remoteSyncing) strings.remoteGitSyncing else strings.remoteGitSyncNow)
        }
        CompactGhostButton(enabled = !model.remoteSyncing, onClick = model::editRemote,
            modifier = Modifier.testTag("settings-remote-edit")) {
            ButtonLabel(strings.remoteGitEdit)
        }
        CompactOutlinedButton(
            contentColor = MaterialTheme.colorScheme.error,
            enabled = !model.remoteSyncing,
            onClick = model::removeRemote,
        ) {
            ButtonLabel(strings.remoteGitRemove)
        }
        Note(strings.remoteGitLocalKept)
    }
}

/** "Up to date · pushed 18 min ago · automatic" — one line for state, freshness and mode. */
private fun remoteStatusLine(model: SettingsModel, strings: Strings): String {
    val state = when (model.remoteSyncStatus) {
        RemoteSyncStatus.PUSHED -> strings.remoteGitPushed
        RemoteSyncStatus.UP_TO_DATE -> strings.remoteGitUpToDate
        RemoteSyncStatus.FAILED -> strings.remoteGitFailed
        null -> strings.remoteGitNotSynced
    }
    val parts = mutableListOf(state)
    model.remoteSyncAtMillis?.let { parts += strings.remoteGitPushedAgo(ago(it, strings)) }
    model.lastRemoteSync?.let { sync ->
        if (sync.behind > 0) parts += strings.remoteGitBehind(sync.behind)
        if (sync.merged) parts += strings.remoteGitMerged
        if (sync.conflicts.isNotEmpty()) parts += strings.remoteGitConflicts(sync.conflicts.size)
        val restored = sync.conflicts.count { it.restored }
        if (restored > 0) parts += strings.remoteGitRestored(restored)
    }
    parts += if (model.remoteAutomatic) strings.remoteGitAutoShort else strings.remoteGitManualShort
    return parts.joinToString(" · ")
}

// ---------------------------------------------------------------- Shortcuts

private fun NameFormat.label(strings: Strings): String = when (this) {
    NameFormat.KEBAB -> strings.nameFormatKebab
    NameFormat.CAMEL -> strings.nameFormatCamel
    NameFormat.SNAKE -> strings.nameFormatSnake
    NameFormat.FREE -> strings.nameFormatFree
}
