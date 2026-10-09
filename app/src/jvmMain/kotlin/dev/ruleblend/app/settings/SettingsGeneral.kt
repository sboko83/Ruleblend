package dev.ruleblend.app.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MiniSwitch
import dev.ruleblend.app.theme.SegmentedControl
import dev.ruleblend.app.util.TRANSLATION_SETTINGS_URL
import dev.ruleblend.app.util.openUrl
import dev.ruleblend.app.util.pickExternalEditor
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.model.LineEnding
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.translate.TranslationStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GeneralSection(model: SettingsModel, narrow: Boolean, operationScope: CoroutineScope) {
    val strings = LocalStrings.current
    SettingsSectionBlock(SettingsSection.GENERAL, strings.setSectionGeneral, strings.setGeneralSum) {
        SettingsRow(strings.setTheme, strings.setThemeHint, narrow) {
            SegmentedControl(
                options = ThemeMode.entries.map { it.label(strings) },
                selected = ThemeMode.entries.indexOf(model.themeMode),
                onSelect = { model.changeThemeMode(ThemeMode.entries[it]) },
                testTagFor = { "settings-theme-${ThemeMode.entries[it].name.lowercase()}" },
            )
        }
        RowDivider()
        SettingsRow(strings.setLineEnding, strings.setLineEndingHint, narrow) {
            SegmentedControl(
                options = listOf("LF", "CRLF"),
                selected = LineEnding.entries.indexOf(model.lineEnding),
                onSelect = { if (!model.lineEndingBusy) operationScope.launch { model.changeLineEnding(LineEnding.entries[it]) } },
                testTagFor = { "settings-line-ending-${LineEnding.entries[it].gitValue}" },
            )
            model.lineEndingFailure?.let { Note(it, MaterialTheme.colorScheme.error) }
        }
        RowDivider()
        SettingsRow(strings.setLanguage, strings.setLanguageHint, narrow) {
            SegmentedControl(
                options = Language.entries.map { it.label },
                selected = Language.entries.indexOf(model.language),
                onSelect = { model.changeLanguage(Language.entries[it]) },
                testTagFor = { "settings-language-${Language.entries[it].name.lowercase()}" },
            )
        }
        RowDivider()
        SettingsRow(strings.setFileEditor, strings.setFileEditorHint, narrow) {
            SegmentedControl(
                options = listOf(strings.fileEditorInApp, strings.fileEditorExternal),
                selected = if (model.useExternalEditor) 1 else 0,
                onSelect = { model.changeExternalEditor(it == 1) },
                testTagFor = { if (it == 0) "settings-editor-app" else "settings-editor-external" },
            )
            // The application only matters once the file leaves Ruleblend.
            if (model.useExternalEditor) {
                ControlRow {
                    Note(strings.fileEditorApplication)
                    PathChip(
                        model.externalEditorPath?.fileName?.toString()?.removeSuffix(".app")
                            ?: strings.fileEditorSystemDefault,
                    )
                    CompactOutlinedButton(
                        onClick = { pickExternalEditor(strings.fileEditorChoose)?.let(model::changeExternalEditorPath) },
                        modifier = Modifier.testTag("settings-editor-choose"),
                    ) {
                        ButtonLabel(strings.fileEditorChoose)
                    }
                    if (model.externalEditorPath != null) {
                        CompactGhostButton(onClick = { model.changeExternalEditorPath(null) },
                            modifier = Modifier.testTag("settings-editor-reset")) {
                            ButtonLabel(strings.fileEditorReset)
                        }
                    }
                }
            }
        }
        RowDivider()
        // Only the application is settable: which agents can be launched is a fact about the
        // machine, read from the shell, not a preference.
        SettingsRow(strings.setTerminal, strings.setTerminalHint, narrow) {
            ControlRow {
                PathChip(
                    model.terminalAppPath?.fileName?.toString()?.removeSuffix(".app")
                        ?: strings.fileEditorSystemDefault,
                )
                CompactOutlinedButton(
                    onClick = { pickExternalEditor(strings.fileEditorChoose)?.let(model::changeTerminalApp) },
                    modifier = Modifier.testTag("settings-terminal-choose"),
                ) {
                    ButtonLabel(strings.fileEditorChoose)
                }
                if (model.terminalAppPath != null) {
                    CompactGhostButton(onClick = { model.changeTerminalApp(null) },
                        modifier = Modifier.testTag("settings-terminal-reset")) {
                        ButtonLabel(strings.fileEditorReset)
                    }
                }
            }
        }
        if (model.translationAvailable) {
            RowDivider()
            SettingsRow(strings.setTranslation, strings.setTranslationHint, narrow) {
                TranslationSettings(model)
            }
        }
        RowDivider()
        SettingsRow(strings.setWorkspace, strings.setWorkspaceHint, narrow) {
            ControlRow {
                FlashButton(strings.setResetColumns, strings.setResetColumnsDone, model::resetColumnWidths,
                    tag = "settings-reset-columns")
                FlashButton(strings.setClearRecent, strings.setClearRecentDone, model::clearRecentPlaces,
                    tag = "settings-clear-recent")
                Note(strings.setWorkspaceNote)
            }
        }
    }
}

/** Test handle for the switch that takes translation out of the app. */
internal const val SettingsTranslationSwitchTag = "settings-translation-enabled"

/**
 * Whether the app offers translation at all, and — when it does — the pair the panel uses and how
 * much it may spend. Language codes come from the system, so this list never drifts from what macOS
 * actually ships.
 */
@Composable
private fun TranslationSettings(model: SettingsModel) {
    val strings = LocalStrings.current
    // Spawning the helper is deferred to first view: opening Settings must not wait on a process.
    LaunchedEffect(model.translation) { if (model.translation.enabled) model.refreshTranslation() }

    ControlRow {
        MiniSwitch(
            checked = model.translation.enabled,
            onCheckedChange = { model.changeTranslation(model.translation.copy(enabled = it)) },
            modifier = Modifier.testTag(SettingsTranslationSwitchTag),
        )
        Text(
            strings.setTranslationEnabled,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Off is off everywhere: the pair, the quality and the language-pack notice belong to a feature
    // that is not on offer, so they go with it instead of sitting here greyed out.
    if (!model.translation.enabled) return
    ControlRow {
        LanguagePicker(
            code = model.translation.sourceLanguage,
            codes = model.translationLanguages,
            tag = "source",
            onPick = { model.changeTranslation(model.translation.copy(sourceLanguage = it)) },
        )
        Text("→", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LanguagePicker(
            code = model.translation.targetLanguage,
            codes = model.translationLanguages,
            tag = "target",
            onPick = { model.changeTranslation(model.translation.copy(targetLanguage = it)) },
        )
        SegmentedControl(
            options = listOf(strings.translateQualityFast, strings.translateQualityHigh),
            selected = if (model.translation.quality == TranslationQuality.HIGH) 1 else 0,
            onSelect = { index ->
                val quality = if (index == 1) TranslationQuality.HIGH else TranslationQuality.LOW
                model.changeTranslation(model.translation.copy(quality = quality))
            },
            testTagFor = { "settings-translation-quality-${if (it == 1) "high" else "low"}" },
        )
    }
    when (model.translationStatus) {
        TranslationStatus.SUPPORTED -> ControlRow {
            Note(strings.translatePackMissing)
            CompactOutlinedButton(onClick = { openUrl(TRANSLATION_SETTINGS_URL) }) {
                ButtonLabel(strings.translateOpenSettings)
            }
        }

        TranslationStatus.UNSUPPORTED -> Note(strings.translateUnsupported)
        TranslationStatus.INSTALLED -> Note(strings.translatePackReady)
        null -> Unit
    }
}

@Composable
private fun LanguagePicker(code: String, codes: List<String>, tag: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        CompactOutlinedButton(onClick = { open = true }, enabled = codes.isNotEmpty(),
            modifier = Modifier.testTag("settings-translation-$tag-language")) {
            ButtonLabel(code.uppercase())
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            codes.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.uppercase(), style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.testTag("settings-translation-$tag-option-$candidate"),
                    onClick = {
                        open = false
                        onPick(candidate)
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Library

private fun ThemeMode.label(strings: Strings): String = when (this) {
    ThemeMode.SYSTEM -> strings.themeSystem
    ThemeMode.LIGHT -> strings.themeLight
    ThemeMode.DARK -> strings.themeDark
}
