package dev.ruleblend.app.assistance

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.settings.SettingsModel
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.RuleblendTooltip
import kotlinx.coroutines.launch

/** One item of the button's menu; a button without items launches [AssistanceButton.target] directly. */
data class AssistanceMenuItem(val label: String, val tag: String, val target: AssistanceObject)

@Composable
fun AssistanceButton(
    target: AssistanceObject?,
    dirty: Boolean,
    settings: SettingsModel,
    onConfigure: () -> Unit,
    onLaunch: (AssistanceObject, ReadyAssistanceAssistant) -> Unit,
    onFailure: (String) -> Unit,
    menu: List<AssistanceMenuItem> = emptyList(),
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(emptyList<ReadyAssistanceAssistant>()) }
    var busy by remember { mutableStateOf(false) }
    var choiceTarget by remember { mutableStateOf<AssistanceObject?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    fun start(selectedTarget: AssistanceObject) {
        busy = true
        scope.launch {
            try {
                settings.refreshAssistanceAssistants()
                when (val selection = selectAssistanceAssistant(settings.assistanceAssistants, settings.readyAssistanceAssistants)) {
                    AssistanceSelection.Configure -> onConfigure()
                    is AssistanceSelection.Launch -> onLaunch(selectedTarget, selection.assistant)
                    is AssistanceSelection.Choose -> { choices = selection.assistants; choiceTarget = selectedTarget }
                }
            } catch (failure: Exception) {
                onFailure(failure.message ?: failure.toString())
            } finally { busy = false }
        }
    }
    Box {
        RuleblendTooltip(if (dirty) strings.assistanceSaveFirst else strings.assistanceOpen) {
            CompactGhostButton(
                enabled = target != null && !dirty && !busy,
                modifier = Modifier.testTag("assistance-open"),
                onClick = {
                    if (menu.isNotEmpty()) menuOpen = true else target?.let(::start)
                },
            ) { Text(strings.assistanceOpen) }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menu.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    modifier = Modifier.testTag(item.tag),
                    onClick = { menuOpen = false; start(item.target) },
                )
            }
        }
        DropdownMenu(expanded = choices.isNotEmpty(), onDismissRequest = { choices = emptyList() }) {
            choices.forEach { assistant ->
                DropdownMenuItem(text = { Text(assistant.name) }, onClick = {
                    choices = emptyList()
                    choiceTarget?.let { onLaunch(it, assistant) }
                })
            }
        }
    }
}
