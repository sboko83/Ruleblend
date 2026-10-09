package dev.ruleblend.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MiniSwitch
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.Pill
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.app.util.copyToClipboard
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentCapability
import dev.ruleblend.core.integration.CapabilityLimit
import dev.ruleblend.core.integration.CapabilityState
import dev.ruleblend.core.integration.capabilities
import dev.ruleblend.mcp.McpConnector
import dev.ruleblend.mcp.McpRegistration
import kotlinx.coroutines.launch

/**
 * One table instead of the two sections the old screen had: an agent, what Ruleblend can manage for
 * it, whether it is visible, and how it is connected to the Ruleblend MCP server — all in one row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentsSection(model: SettingsModel) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val visible = model.agents.count { it.id !in model.hiddenAgents }
    val connected = model.connectStates.count { it.value == ConnectState.CONNECTED }
    val summary = strings.setAgentSummary(visible, model.agents.size, connected, model.connectStates.size)
    SettingsSectionBlock(SettingsSection.AGENTS, strings.setSectionAgents, summary) {
        AgentHeader()
        model.agents.forEach { agent ->
            RowDivider()
            AgentRow(model, agent)
        }
        // One click for the whole machine: after an app update every connected assistant is
        // behind by the same release, and repairing them one row at a time is busywork.
        if (model.outdatedAgentIds.isNotEmpty()) {
            RowDivider()
            ControlRow(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Note(strings.setUpdateAllHint(model.outdatedAgentIds.size), RuleblendTheme.extraColors.warning)
                CompactOutlinedButton(
                    onClick = { scope.launch { model.updateAllIntegrations() } },
                    modifier = Modifier.testTag("settings-update-all"),
                ) {
                    ButtonLabel(strings.setUpdateAll)
                }
            }
        }
        RowDivider()
        model.launch?.let { launch ->
            val command = launch.shellCommand()
            ControlRow(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Note(strings.connectManualHint)
                PathChip(command)
                FlashButton(strings.setCopy, strings.setCopied, onClick = { copyToClipboard(command) })
            }
        }
        model.launchFailure?.let {
            Note(it, MaterialTheme.colorScheme.error,
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp).testTag("settings-launch-error"))
        }
        model.connectFailure?.let {
            Note(it, MaterialTheme.colorScheme.error, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        RowDivider()
        Foot(strings.setAgentsFoot)
    }
}

/**
 * Column weights shared by the header and every row, so the table stays a table. Can-manage carries
 * three chips and gets the room for them; the agent's own column can afford it, its second line is a
 * path that ellipsizes anyway.
 */
private const val AgentColumn = 0.24f
private const val ManageColumn = 0.24f
private const val VisibleColumn = 0.08f
private const val CliColumn = 0.20f
private const val ConnectColumn = 0.24f

@Composable
private fun AgentHeader() {
    val strings = LocalStrings.current
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.setColAgent.uppercase(), style = SectionHeaderStyle, modifier = Modifier.weight(AgentColumn))
        Text(strings.setColCanManage.uppercase(), style = SectionHeaderStyle, modifier = Modifier.weight(ManageColumn))
        Text(strings.setColVisible.uppercase(), style = SectionHeaderStyle, modifier = Modifier.weight(VisibleColumn))
        Text(strings.setColCli.uppercase(), style = SectionHeaderStyle, modifier = Modifier.weight(CliColumn))
        Text(strings.setColMcp.uppercase(), style = SectionHeaderStyle, modifier = Modifier.weight(ConnectColumn))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentRow(model: SettingsModel, agent: AgentAdapter) {
    val strings = LocalStrings.current
    val hidden = agent.id in model.hiddenAgents
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 9.dp).testTag("settings-agent-${agent.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(AgentColumn), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                // An agent that is not on this machine still has a row: its file is where it would go.
                if (!agent.isAvailable()) {
                    Pill(
                        strings.setAgentNotDetected,
                        MaterialTheme.colorScheme.background,
                        RuleblendTheme.extraColors.faint,
                    )
                }
            }
            Text(
                agent.globalFile().abbreviateHome(),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // FlowRow, not Row: at a narrow window the three chips wrap instead of being clipped.
        FlowRow(
            Modifier.weight(ManageColumn),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val capabilities = agent.capabilities()
            Capability(strings.setAgentRulesCapability, capabilities.rules, "rules")
            Capability(strings.setAgentMcpCapability, capabilities.mcp, "mcp")
            Capability(strings.setAgentSkillsCapability, capabilities.skills, "skills")
            Capability(strings.setAgentSubagentsCapability, capabilities.subagents, "subagents")
        }
        Row(Modifier.weight(VisibleColumn)) {
            MiniSwitch(
                checked = !hidden,
                onCheckedChange = { shown -> model.setAgentHidden(agent, !shown) },
                modifier = Modifier.testTag("settings-visible-${agent.id}"),
            )
        }
        Box(Modifier.weight(CliColumn)) { CliArguments(model, agent) }
        ControlRow(Modifier.weight(ConnectColumn)) { Connection(model, agent) }
    }
}

/**
 * The argument line appended to this agent's launch from a project header. Empty is the normal
 * state, so the field shows the bare command it would run instead of a label; a machine without
 * this CLI has nothing to configure and says so.
 *
 * Saved when the field is left rather than on every keystroke: the config file is rewritten on
 * every change, and a half-typed flag is not a setting yet.
 */
@Composable
private fun CliArguments(model: SettingsModel, agent: AgentAdapter) {
    val cli = agent.cli
    if (cli == null) {
        Note("—", RuleblendTheme.extraColors.faint)
        return
    }
    val saved = model.agentCliArguments[agent.id].orEmpty()
    var text by remember(agent.id, saved) { mutableStateOf(saved) }
    RuleblendTooltip(cliHint(cli.executable)) {
        RuleblendOutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            placeholder = {
                Text(
                    cli.executable,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
                    color = RuleblendTheme.extraColors.faint,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-cli-${agent.id}")
                .onFocusChanged { state -> if (!state.isFocused && text != saved) model.changeAgentCliArguments(agent.id, text) },
        )
    }
}

/** What the launch will actually run, so the argument field is read against a real command line. */
@Composable
private fun cliHint(executable: String): String = "${LocalStrings.current.setCliArgumentsHint}: $executable …"


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Connection(model: SettingsModel, agent: AgentAdapter) {
    val strings = LocalStrings.current
    val extra = RuleblendTheme.extraColors
    val state = model.connectStates[agent.id]
    // No connector on this machine: the agent is wired by hand with the command below the table.
    if (state == null) {
        Note(strings.setConnManual)
        return
    }
    val connector = model.connectors.find { it.agentId == agent.id } ?: return
    StatusDot(
        when (state) {
            ConnectState.CONNECTED -> extra.success
            ConnectState.NOT_CONNECTED -> extra.faint
            // Both mean "refresh this", which is the one colour an update wears everywhere; the
            // line beside the dot is what separates a stale config from an outdated skill.
            ConnectState.STALE -> extra.warning
            ConnectState.SKILL_OUTDATED -> extra.warning
            ConnectState.MCP_OUTDATED -> extra.warning
            ConnectState.SKILL_FOREIGN -> extra.danger
            ConnectState.MCP_FOREIGN -> extra.danger
        },
    )
    Note(
        state.label(strings),
        when (state) {
            ConnectState.STALE -> extra.warning
            ConnectState.SKILL_FOREIGN -> MaterialTheme.colorScheme.error
            ConnectState.MCP_FOREIGN -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    Versions(model, agent.id)
    ConnectActions(model, connector, state)
}

/**
 * What this agent actually holds, beside the word for it: "skill v3 → v5" is the difference between
 * "out of date" and knowing whether the copy is one release behind or five.
 */
@Composable
private fun Versions(model: SettingsModel, agentId: String) {
    val strings = LocalStrings.current
    val state = model.integrationStates.find { it.agentId == agentId } ?: return
    val parts = listOfNotNull(
        state.skill?.let { skill -> skill.installed?.let { strings.setSkillVersion(it, skill.current) } },
        state.mcp
            ?.takeIf { it.registration != McpRegistration.NOT_REGISTERED }
            ?.let { mcp -> mcp.installed?.let { strings.setEntryVersion(it, mcp.current) } },
    )
    if (parts.isEmpty()) return
    Note(
        parts.joinToString(" · "),
        if (state.outdated) RuleblendTheme.extraColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
        Modifier.testTag("settings-versions-$agentId"),
    )
}

@Composable
private fun ConnectActions(model: SettingsModel, connector: McpConnector, state: ConnectState) {
    val strings = LocalStrings.current
    // Registering an agent writes its config and probes its CLI: not work for the click's own frame.
    val scope = rememberCoroutineScope()
    // A foreign skill is the user's own file: Connect would only fail, so only Disconnect is offered.
    val outdated = state == ConnectState.SKILL_OUTDATED || state == ConnectState.MCP_OUTDATED
    if (state != ConnectState.CONNECTED && state != ConnectState.SKILL_FOREIGN && state != ConnectState.MCP_FOREIGN) {
        CompactOutlinedButton(
            enabled = model.launch != null,
            onClick = {
                scope.launch { if (outdated) model.updateAgent(connector.agentId) else model.connect(connector) }
            },
            modifier = Modifier.testTag("settings-connect-${connector.agentId}"),
        ) {
            ButtonLabel(if (outdated) strings.connectActionUpdate else strings.connectAction)
        }
    }
    if (state != ConnectState.NOT_CONNECTED && state != ConnectState.MCP_FOREIGN) {
        CompactOutlinedButton(
            contentColor = MaterialTheme.colorScheme.error,
            onClick = { scope.launch { model.disconnect(connector) } },
            modifier = Modifier.testTag("settings-disconnect-${connector.agentId}"),
        ) {
            ButtonLabel(strings.disconnectAction)
        }
    }
}

/** What Ruleblend can manage for this agent, including a scope limit rather than an optimistic tick. */
@Composable
private fun Capability(label: String, capability: AgentCapability, id: String) {
    val strings = LocalStrings.current
    val shape = MaterialTheme.shapes.extraSmall
    val detail = when (capability.limit) {
        CapabilityLimit.GLOBAL_ONLY -> strings.setCapabilityGlobalOnly
        CapabilityLimit.PROJECT_ONLY -> strings.setCapabilityProjectOnly
        CapabilityLimit.NOT_SUPPORTED -> strings.setCapabilityUnavailable
        null -> null
    }
    val text = buildString {
        append(
            when (capability.state) {
                CapabilityState.SUPPORTED -> "✓"
                CapabilityState.PARTIAL -> "◐"
                CapabilityState.UNSUPPORTED -> "✗"
            },
        )
        append(' ').append(label)
        detail?.let { append(" · ").append(it) }
    }
    val active = capability.state == CapabilityState.SUPPORTED
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (active) MaterialTheme.colorScheme.primary else RuleblendTheme.extraColors.faint,
        textDecoration = if (active) null else TextDecoration.LineThrough,
        modifier = Modifier
            .clip(shape)
            .background(if (active) RuleblendTheme.extraColors.accentBackground else Color.Transparent)
            .then(if (active) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .testTag("settings-capability-$id"),
    )
}

private fun ConnectState.label(strings: Strings): String = when (this) {
    ConnectState.CONNECTED -> strings.connectStatusConnected
    ConnectState.NOT_CONNECTED -> strings.connectStatusNotConnected
    ConnectState.STALE -> strings.connectStatusStale
    ConnectState.SKILL_OUTDATED -> strings.connectStatusSkillOutdated
    ConnectState.MCP_OUTDATED -> strings.connectStatusMcpOutdated
    ConnectState.SKILL_FOREIGN -> strings.connectStatusSkillForeign
    ConnectState.MCP_FOREIGN -> strings.connectStatusMcpForeign
}
