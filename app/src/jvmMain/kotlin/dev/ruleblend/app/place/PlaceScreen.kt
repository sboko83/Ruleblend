package dev.ruleblend.app.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.integration.displayName
import dev.ruleblend.app.library.ContentWithTranslation
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.summary
import dev.ruleblend.app.library.TranslationControls
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.app.navigation.PaneMinWidth
import dev.ruleblend.app.navigation.fitPanes
import dev.ruleblend.app.navigation.paneMaxWidth
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendConfirmDialog
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.SplitPane
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.app.theme.workingDialogSize
import dev.ruleblend.app.util.FailureDialog
import dev.ruleblend.app.util.openInExternalEditor
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.usecase.SkipReason
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * One place: the list of places on the left, the file the agent actually reads in the centre, the
 * library palette on the right. Everything this screen writes goes through the columns themselves —
 * the screen only hosts the dialogs that outlive a single column: removing a project and the
 * in-app file editor.
 */
@Composable
fun PlaceScreen(
    model: IntegrationModel,
    focusChanges: Boolean = false,
    onEditBlock: (LibraryObjectKey) -> Unit = {},
    useExternalEditor: Boolean = false,
    externalEditor: Path? = null,
    groupsExpandedByDefault: Boolean = true,
    placeLibraryExpandedByDefault: Boolean = true,
    placeRulesExpandedByDefault: Boolean = false,
    placeSkillsExpandedByDefault: Boolean = false,
    placeSubagentsExpandedByDefault: Boolean = false,
    placeMcpExpandedByDefault: Boolean = false,
    translation: TranslationModel? = null,
    modifier: Modifier = Modifier,
) {
    // Removing a project and saving a file are disk work: the click hands them to a coroutine.
    val scope = rememberCoroutineScope()
    // Removing a project from Ruleblend is reversible only by re-adding it, so confirm before doing it.
    var removingProject by remember { mutableStateOf<ProjectTarget?>(null) }
    // The in-app editor opens on demand: a manual fix to a found file, without leaving the app.
    var editing by remember { mutableStateOf<Path?>(null) }
    var paletteTab by remember(model.selected) { mutableStateOf<PlaceTabKind?>(null) }
    // Set recommendations compare this place against its siblings, so the scan runs when the place
    // changes and after every write — an install here changes "in N of M" for the whole set. It
    // reads every place off the UI thread and never blocks the column it feeds.
    LaunchedEffect(model.selected, model.writeRevision) { model.refreshUsage() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // The file preview is the centre of this surface, so the side columns give way to it: they
        // shrink first and, in a window too narrow for both, the place list goes before the palette —
        // places are still one ⌘K away, whereas nothing else installs into this file.
        val paletteRequest = if (model.selected == null) 0.dp else model.columnWidths.placePalette.dp
        val available = maxWidth
        val fit = fitPanes(
            available = available,
            leading = model.columnWidths.integrationSidebar.dp,
            trailing = paletteRequest,
        )
        Row(Modifier.fillMaxSize()) {
            fit.leading?.let { width ->
                SplitPane(
                    width = width.value.roundToInt(),
                    onWidthChange = { model.saveSidebarWidth(it) },
                    min = PaneMinWidth.value.roundToInt(),
                    max = paneMaxWidth(available, paletteRequest).value.roundToInt(),
                ) {
                    PlaceListColumn(
                        model,
                        groupsExpandedByDefault = groupsExpandedByDefault,
                        onRemoveProject = { removingProject = it },
                    )
                }
            }
            if (model.selected == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text(LocalStrings.current.intEmptySelection) }
            } else {
                PlaceFileColumn(
                    model,
                    focusChanges = focusChanges,
                    rulesExpandedByDefault = placeRulesExpandedByDefault,
                    skillsExpandedByDefault = placeSkillsExpandedByDefault,
                    subagentsExpandedByDefault = placeSubagentsExpandedByDefault,
                    mcpExpandedByDefault = placeMcpExpandedByDefault,
                    onEditFile = { path ->
                        editFile(path, useExternalEditor, externalEditor, model) { editing = it }
                    },
                    onEditBlock = onEditBlock,
                    translation = translation,
                    onTabSelected = { paletteTab = it },
                    modifier = Modifier.weight(1f),
                )
                fit.trailing?.let { width ->
                    SplitPane(
                        width = width.value.roundToInt(),
                        onWidthChange = { model.savePaletteWidth(it) },
                        min = PaneMinWidth.value.roundToInt(),
                        max = paneMaxWidth(available, fit.leading ?: 0.dp).value.roundToInt(),
                        dividerAtStart = true,
                    ) {
                        PlacePaletteColumn(
                            model,
                            onEditFile = { path ->
                                editFile(path, useExternalEditor, externalEditor, model) { editing = it }
                            },
                            libraryExpandedByDefault = placeLibraryExpandedByDefault,
                            translation = translation,
                            selectedTab = paletteTab,
                        )
                    }
                }
            }
        }
    }

    removingProject?.let { target ->
        val strings = LocalStrings.current
        RuleblendConfirmDialog(
            text = strings.intRemoveProjectConfirm(target.name),
            confirmLabel = strings.ctxRemoveProject,
            dismissLabel = strings.actionCancel,
            onDismiss = { removingProject = null },
            onConfirm = { scope.launch { model.removeProject(target) } },
        )
    }

    editing?.let { path ->
        FileEditDialog(
            path = path,
            targets = model.agentTargets + model.projectTargets,
            translation = translation,
            onDismiss = { editing = null },
            onSave = { text ->
                scope.launch { model.editFile(path, text) }
                editing = null
            },
            onSaveAsRule = { name, content -> scope.launch { model.saveAsRule(name, content) } },
        )
    }

    model.failure?.let { FailureDialog(it, onDismiss = model::clearFailure) }

    // Only when something was left alone: a bundle that went in whole says so by the rows it changed,
    // and a dialog after every group action would be noise. A skipped member has no other voice.
    // Same rule as the group report: a switch that went through whole speaks through the rows it
    // changed. Only a protected copy, a write that failed or a binding whose profile is gone needs
    // saying out loud.
    model.profileReport?.takeIf { it.skipped.isNotEmpty() || it.failed > 0 || it.missingProfiles.isNotEmpty() }?.let { report ->
        val strings = LocalStrings.current
        RuleblendDialog(
            onDismiss = model::clearProfileReport,
            title = strings.placeProfileReportTitle,
            text = buildList {
                add(strings.placeProfileReportApplied(report.installed + report.retagged, report.removed))
                report.skipped[SkipReason.OUT_OF_SCOPE]?.let { add(strings.libInstallSkippedScope(it)) }
                report.skipped[SkipReason.UNSUPPORTED]?.let { add(strings.libInstallSkippedUnsupported(it)) }
                report.skipped[SkipReason.MODIFIED]?.let { add(strings.libInstallSkippedModified(it)) }
                if (report.failed > 0) add(strings.libInstallFailedCount(report.failed))
                report.missingProfiles.takeIf { it.isNotEmpty() }
                    ?.let { add(strings.placeProfileReportMissing(it.joinToString(", "))) }
            }.joinToString("\n"),
            confirmLabel = strings.actionOk,
            onConfirm = model::clearProfileReport,
        )
    }

    model.groupReport?.takeIf { it.report.skippedTotal > 0 || it.unmarked.isNotEmpty() }?.let { outcome ->
        val strings = LocalStrings.current
        val summary = outcome.report.summary(strings, removal = outcome.removal)
        if (outcome.unmarked.isEmpty()) {
            RuleblendDialog(
                onDismiss = model::clearGroupReport,
                title = if (outcome.removal) strings.libUninstallReportTitle else strings.libInstallReportTitle,
                text = summary,
                confirmLabel = strings.actionOk,
                onConfirm = model::clearGroupReport,
            )
        } else {
            // Nothing tells a standalone copy from one an older build wrote for the group: the
            // person decides, and keeping them is the default way out.
            RuleblendDialog(
                onDismiss = model::clearGroupReport,
                title = strings.libUninstallReportTitle,
                text = summary + "\n" + strings.placeGroupUnmarked(outcome.unmarked.joinToString(", ") { it.displayName }),
                confirmLabel = strings.placeGroupRemoveUnmarked,
                onConfirm = { scope.launch { model.removeUnmarkedGroupMembers() } },
                destructive = true,
                dismissLabel = strings.placeGroupKeepUnmarked,
            )
        }
    }
}

private fun editFile(
    path: Path,
    useExternalEditor: Boolean,
    externalEditor: Path?,
    model: IntegrationModel,
    showInApp: (Path) -> Unit,
) {
    if (!useExternalEditor) {
        showInApp(path)
        return
    }
    runCatching { openInExternalEditor(path, externalEditor) }.onFailure(model::reportFailure)
}

/**
 * A plain text editor for a found file: loads the whole file (managed regions included) so the user
 * can make a manual fix without leaving the app. Save is disabled until the text actually changes,
 * and the write is atomic — the same temp+rename every other Ruleblend write uses.
 *
 * It is the library editor for a file that is not in the library, so it is built like one: the whole
 * window minus a margin, and the same translation split — a rule found in a project is as likely to
 * be in a language the reader does not write in as a rule already adopted.
 */
@Composable
private fun FileEditDialog(
    path: Path,
    targets: List<Target>,
    translation: TranslationModel?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onSaveAsRule: (name: String, content: String) -> Unit,
) {
    val strings = LocalStrings.current
    // Read once per open: the file does not change under us while the dialog is up, and capturing the
    // original text is what makes Save enable only on a real change.
    val original = remember(path) { runCatching { path.readText() }.getOrElse { "" } }
    var text by remember(path) { mutableStateOf(original) }
    var comparing by remember(path) { mutableStateOf(false) }
    // A translation belongs to the text it was made from; another file is another text.
    LaunchedEffect(path) { translation?.reset() }
    val dialogSize = workingDialogSize()
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.intFileEditorTitle(path.fileName?.toString() ?: path.toString()),
        confirmLabel = strings.actionSave,
        confirmEnabled = text != original,
        dismissLabel = strings.actionCancel,
        onConfirm = { onSave(text) },
        modifier = Modifier.size(dialogSize),
        minWidth = dialogSize.width,
        maxWidth = dialogSize.width,
        usePlatformDefaultWidth = false,
    ) {
        // Reading a rule and reading what another project made of the same rule are the two questions
        // this editor gets asked, so both sit on the same strip above the text.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactOutlinedButton(
                onClick = { comparing = true },
                modifier = Modifier.testTag(FileEditCompareTag),
            ) { Text(strings.compareFileOpen) }
            translation?.let {
                TranslationControls(
                    model = it,
                    content = text,
                    canReplace = true,
                    onReplace = { translated -> text = translated },
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // No mark: what is open here is the whole file, markers and hand-written text together,
            // and a file is not a thing that can be in sync or out of it. The accent mat says
            // "Ruleblend is showing you this" without claiming a verdict on it.
            ContentWithTranslation(translation, text, mark = null) { fieldModifier ->
                RuleblendOutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = fieldModifier,
                )
            }
        }
    }

    if (comparing) {
        FileComparePane(
            fileName = path.fileName?.toString() ?: path.toString(),
            text = text,
            targets = targets,
            path = path,
            onApply = { merged ->
                text = merged
                comparing = false
            },
            onSaveAsRule = onSaveAsRule,
            onDismiss = { comparing = false },
        )
    }
}
