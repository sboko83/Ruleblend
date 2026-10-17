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
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import kotlinx.coroutines.launch

/** One item of the button's menu; a button without items launches [AssistanceButton.target] directly. */
data class AssistanceMenuItem(val label: String, val tag: String, val target: AssistanceObject)

/** Only a handed-off launch leaves [starting] to [AssistanceSessions.open]; every other ending clears it. */
internal suspend fun launchAssistance(
    sessions: AssistanceSessions?,
    starting: AssistanceStarting?,
    select: suspend () -> AssistanceSelection,
    onConfigure: () -> Unit,
    onLaunch: (ReadyAssistanceAssistant) -> Unit,
    onChoose: (List<ReadyAssistanceAssistant>) -> Unit,
    onFailure: (String) -> Unit,
) {
    var launched = false
    try {
        when (val selection = select()) {
            AssistanceSelection.Configure -> onConfigure()
            is AssistanceSelection.Launch -> { onLaunch(selection.assistant); launched = true }
            is AssistanceSelection.Choose -> onChoose(selection.assistants)
        }
    } catch (failure: Exception) {
        onFailure(failure.message ?: failure.toString())
    } finally {
        if (!launched) starting?.let { sessions?.end(it) }
    }
}

@Composable
fun AssistanceButton(
    target: AssistanceObject?,
    dirty: Boolean,
    settings: SettingsModel,
    onConfigure: () -> Unit,
    onLaunch: (AssistanceObject, ReadyAssistanceAssistant) -> Unit,
    onFailure: (String) -> Unit,
    menu: List<AssistanceMenuItem> = emptyList(),
    /** A bare icon with the label in its tooltip, for a row of other icon actions. */
    iconOnly: Boolean = false,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(emptyList<ReadyAssistanceAssistant>()) }
    var busy by remember { mutableStateOf(false) }
    var choiceTarget by remember { mutableStateOf<AssistanceObject?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val sessions = LocalAssistanceSessions.current
    fun start(selectedTarget: AssistanceObject) {
        val starting = when (val begin = sessions?.begin(selectedTarget)) {
            AssistanceBegin.Busy -> return
            is AssistanceStarting -> begin
            AssistanceBegin.Current, null -> null
        }
        busy = true
        scope.launch {
            try {
                launchAssistance(
                    sessions, starting,
                    select = {
                        val refreshedAt = System.nanoTime()
                        settings.refreshAssistanceAssistants()
                        logAssistanceTiming("assistant refresh", refreshedAt)
                        selectAssistanceAssistant(settings.assistanceAssistants, settings.readyAssistanceAssistants)
                    },
                    onConfigure = onConfigure,
                    onLaunch = { onLaunch(selectedTarget, it) },
                    onChoose = { choices = it; choiceTarget = selectedTarget },
                    onFailure = onFailure,
                )
            } finally { busy = false }
        }
    }
    Box {
        val tooltip = if (dirty) strings.assistanceSaveFirst else strings.assistanceOpen
        val enabled = target != null && !dirty && !busy
        val onClick = { if (menu.isNotEmpty()) menuOpen = true else target?.let(::start) }
        if (iconOnly) {
            IconActionButton(
                icon = RuleblendTheme.icons.assistance,
                label = tooltip,
                onClick = { onClick() },
                enabled = enabled,
                modifier = Modifier.testTag("assistance-open"),
            )
        } else {
            RuleblendTooltip(tooltip) {
                CompactGhostButton(
                    enabled = enabled,
                    modifier = Modifier.testTag("assistance-open"),
                    onClick = { onClick() },
                ) { Text(strings.assistanceOpen) }
            }
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
                    choiceTarget?.let { target ->
                        runCatching { onLaunch(target, assistant) }.onFailure { onFailure(it.message ?: it.toString()) }
                    }
                })
            }
        }
    }
}
