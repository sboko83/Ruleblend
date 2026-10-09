package dev.ruleblend.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.navigation.AppRoute
import dev.ruleblend.app.navigation.shortcutLabel
import dev.ruleblend.app.navigation.commandShortcut
import dev.ruleblend.app.theme.MonoFontFamily

@Composable
internal fun ShortcutsSection(narrow: Boolean) {
    val strings = LocalStrings.current
    // The palette and Esc are the two chords no route owns, so they are spelled out here; the rest
    // come from the same table the shell binds them from.
    val keys = listOf(
        strings.setKeyPalette to listOf(commandShortcut("K")),
        strings.navSettings to listOfNotNull(shortcutLabel(AppRoute.SETTINGS)),
        strings.navHome to listOfNotNull(shortcutLabel(AppRoute.HOME)),
        strings.navPlace to listOfNotNull(shortcutLabel(AppRoute.PLACE)),
        strings.navLibrary to listOfNotNull(shortcutLabel(AppRoute.LIBRARY)),
        strings.navCoverage to listOfNotNull(shortcutLabel(AppRoute.COVERAGE)),
        strings.setKeyClose to listOf("Esc"),
        strings.setKeyScoped to listOf("r:", "s:", "m:", "p:"),
    )
    SettingsSectionBlock(SettingsSection.SHORTCUTS, strings.setSectionShortcuts, strings.setShortcutsSum) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            keys.chunked(if (narrow) 1 else 2).forEachIndexed { index, pair ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    pair.forEach { (label, chords) -> KeyRow(label, chords, Modifier.weight(1f)) }
                    if (pair.size == 1 && !narrow) Spacer(Modifier.weight(1f))
                }
            }
        }
        RowDivider()
        Foot(strings.setShortcutsFoot)
    }
}

@Composable
private fun KeyRow(label: String, chords: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { chords.forEach { Kbd(it) } }
    }
}

@Composable
private fun Kbd(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// ---------------------------------------------------------------- About
