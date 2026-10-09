package dev.ruleblend.app.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import dev.ruleblend.app.util.tabSemantics
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.home.HomeModel
import dev.ruleblend.app.library.LibraryDialog
import dev.ruleblend.app.library.LibraryDialogHost
import dev.ruleblend.app.library.LibraryModel
import dev.ruleblend.app.theme.RuleblendTheme

/** The five stops of the section navigation, in the order they are laid out. */
enum class SettingsSection { GENERAL, FOLDING, LIBRARY, AGENTS, SHORTCUTS, ABOUT }

/** Below this the section navigation goes horizontal and every row stacks label over control. */
private val NarrowWidth = 860.dp
private val NavWidth = 168.dp
private val ContentMaxWidth = 1040.dp

/**
 * Machine-local configuration: the navigation picks one section and only that section is on screen,
 * so a setting is either in front of the user or one click away. Everything is saved as it is
 * changed — there is no Apply.
 */
@Composable
fun SettingsScreen(
    model: SettingsModel,
    library: LibraryModel,
    modifier: Modifier = Modifier,
    home: HomeModel? = null,
    initialSection: SettingsSection = SettingsSection.GENERAL,
    operationScope: CoroutineScope = rememberCoroutineScope(),
) {
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var active by remember { mutableStateOf(initialSection) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < NarrowWidth
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().padding(20.dp)) {
                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SectionNav(active, narrow = true, onSelect = { active = it })
                        Section(
                            active,
                            model,
                            library,
                            home,
                            operationScope,
                            narrow = true,
                            onDialog = { dialog = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        SectionNav(
                            active,
                            narrow = false,
                            onSelect = { active = it },
                            modifier = Modifier.width(NavWidth),
                        )
                        Section(
                            active,
                            model,
                            library,
                            home,
                            operationScope,
                            narrow = false,
                            onDialog = { dialog = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    LibraryDialogHost(library, dialog, onDismiss = { dialog = null })
}

@Composable
private fun SectionNav(
    active: SettingsSection,
    narrow: Boolean,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val links = @Composable {
        SettingsSection.entries.forEach { section ->
            val on = section == active
            Text(
                section.title(strings),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (on) FontWeight.SemiBold else null,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(if (on) RuleblendTheme.extraColors.accentBackground else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onSelect(section) })
                    .tabSemantics("${strings.navSettings}: ${section.title(strings)}", on)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("settings-nav-${section.name.lowercase()}"),
            )
        }
    }
    if (narrow) {
        FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) { links() }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) { links() }
}

/** The one section the navigation has selected; each gets its own scroll, reset when it is entered. */
@Composable
private fun Section(
    section: SettingsSection,
    model: SettingsModel,
    library: LibraryModel,
    home: HomeModel?,
    operationScope: CoroutineScope,
    narrow: Boolean,
    onDialog: (LibraryDialog?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scroll = remember(section) { ScrollState(0) }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(scroll).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            when (section) {
                SettingsSection.GENERAL -> GeneralSection(model, narrow, operationScope)
                SettingsSection.FOLDING -> FoldingSection(model, narrow)
                SettingsSection.LIBRARY -> LibrarySection(model, library, home, narrow, onDialog, operationScope)
                SettingsSection.AGENTS -> AgentsSection(model)
                SettingsSection.SHORTCUTS -> ShortcutsSection(narrow)
                SettingsSection.ABOUT -> AboutSection(model, narrow)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text(
                strings.setFooter,
                style = MaterialTheme.typography.bodySmall,
                color = RuleblendTheme.extraColors.faint,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

// ---------------------------------------------------------------- Folding

private fun SettingsSection.title(strings: Strings): String = when (this) {
    SettingsSection.GENERAL -> strings.setSectionGeneral
    SettingsSection.FOLDING -> strings.setSectionFolding
    SettingsSection.LIBRARY -> strings.setSectionLibrary
    SettingsSection.AGENTS -> strings.setSectionAgents
    SettingsSection.SHORTCUTS -> strings.setSectionShortcuts
    SettingsSection.ABOUT -> strings.setSectionAbout
}
