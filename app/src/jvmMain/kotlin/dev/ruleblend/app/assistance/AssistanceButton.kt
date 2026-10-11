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

@Composable
fun AssistanceButton(
    target: AssistanceObject?,
    dirty: Boolean,
    settings: SettingsModel,
    onConfigure: () -> Unit,
    onLaunch: (AssistanceObject, ReadyAssistanceAssistant) -> Unit,
    onFailure: (String) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(emptyList<ReadyAssistanceAssistant>()) }
    var busy by remember { mutableStateOf(false) }
    var choiceTarget by remember { mutableStateOf<AssistanceObject?>(null) }
    Box {
        RuleblendTooltip(if (dirty) strings.assistanceSaveFirst else strings.assistanceOpen) {
            CompactGhostButton(
                enabled = target != null && !dirty && !busy,
                modifier = Modifier.testTag("assistance-open"),
                onClick = {
                    val selectedTarget = target ?: return@CompactGhostButton
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
                },
            ) { Text(strings.assistanceOpen) }
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
