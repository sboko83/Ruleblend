package dev.ruleblend.app.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.build.BuildInfo
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.app.util.copyToClipboard
import dev.ruleblend.app.util.revealInFileManager
import java.nio.file.Path

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutSection(model: SettingsModel, narrow: Boolean) {
    val strings = LocalStrings.current
    SettingsSectionBlock(SettingsSection.ABOUT, strings.setSectionAbout, null) {
        SettingsRow(
            "Ruleblend ${BuildInfo.VERSION}",
            strings.setAboutBuild(
                BuildInfo.BUILD,
                System.getProperty("os.name"),
                System.getProperty("java.specification.version"),
            ),
            narrow,
        ) {
            ControlRow {
                FlashButton(strings.setCopyDiagnostics, strings.setCopied,
                    onClick = { copyToClipboard(model.diagnostics()) }, tag = "settings-copy-diagnostics")
                Note(strings.setDiagnosticsNote)
            }
        }
        RowDivider()
        SettingsRow(strings.setFiles, strings.setFilesHint, narrow) {
            FileRow(strings.setFileConfig, model.configPath)
            FileRow(strings.setFileLibrary, model.libraryPath)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileRow(label: String, path: Path?) {
    val strings = LocalStrings.current
    ControlRow {
        Note(label, modifier = Modifier.width(64.dp))
        PathChip(path?.abbreviateHome() ?: "-")
        if (path != null) {
            CompactGhostButton(onClick = { revealInFileManager(path) }) { ButtonLabel(strings.setReveal) }
        }
    }
}

// ---------------------------------------------------------------- Shared row primitives
