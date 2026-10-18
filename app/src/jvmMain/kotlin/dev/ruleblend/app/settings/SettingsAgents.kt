package dev.ruleblend.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MiniSwitch
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
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
import dev.ruleblend.mcp.skill.SkillStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val VisibilityWidth = 88.dp
private val AssistanceWidth = 108.dp
private val IntegrationWidth = 150.dp
private val DetailsPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

@Composable
private fun agentDetailsWidth(): Dp {
    val strings = LocalStrings.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall
    val textWidth = listOf(strings.setAgentDetails, strings.setAgentCollapse).maxOf {
        measurer.measure(it, style, softWrap = false).size.width
    }
    return with(LocalDensity.current) {
        maxOf(78.dp, textWidth.toDp() + 22.dp)
    }
}

/** Presentation state never changes which assistants are selected or which integrations are installed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentsSection(model: SettingsModel) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AgentCatalogFilter.ALL) }
    var page by remember { mutableStateOf(1) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var manual by remember { mutableStateOf(false) }
    val attention = model.integrationStates.filter { it.blocked || it.outdated || it.incomplete }.map { it.agentId }.toSet()
    val catalog = agentCatalogPage(model.agents, model.detectedAgentIds, attention, query, filter, page)
    val ready = model.readyAssistanceAssistants.map { it.id }.toSet()
    val detailsWidth = agentDetailsWidth()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        model.launchFailure?.let {
            Note(it, MaterialTheme.colorScheme.error, Modifier.testTag("settings-launch-error"))
        }
        model.connectFailure?.let { Note(it, MaterialTheme.colorScheme.error) }
        ControlRow {
            Note(strings.setAgentRange(catalog.first, catalog.last, catalog.total), modifier = Modifier.testTag("settings-agents-count"))
            Note(strings.setAssistanceCount(model.assistanceAssistants.size))
        }
        RuleblendOutlinedTextField(
            value = query,
            onValueChange = { query = it; page = 1 },
            placeholder = { Text(strings.placeAssistantsSearch, style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth().testTag("settings-agents-search"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentCatalogFilter.entries.forEach { choice ->
                val label = when (choice) {
                    AgentCatalogFilter.ALL -> strings.setAgentAll
                    AgentCatalogFilter.DETECTED -> strings.setAgentDetected
                    AgentCatalogFilter.ATTENTION -> strings.setAgentAttention
                }
                val count = when (choice) {
                    AgentCatalogFilter.ALL -> model.agents.size
                    AgentCatalogFilter.DETECTED -> model.detectedAgentIds.size
                    AgentCatalogFilter.ATTENTION -> attention.count { it in model.detectedAgentIds }
                }
                CompactOutlinedButton(
                    onClick = { filter = choice; page = 1 },
                    contentColor = if (choice == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-agents-filter-${choice.name.lowercase()}")
                        .semantics { selected = choice == filter },
                ) { ButtonLabel("$label · $count") }
            }
        }
        if (model.outdatedAgentIds.isNotEmpty()) ControlRow {
            Note(strings.setUpdateAllHint(model.outdatedAgentIds.size), RuleblendTheme.extraColors.warning)
            CompactOutlinedButton(
                onClick = { scope.launch { model.updateAllIntegrations() } },
                modifier = Modifier.testTag("settings-update-all"),
            ) { ButtonLabel(strings.setUpdateAll) }
        }
        SettingsSectionBlock(SettingsSection.AGENTS, strings.setSectionAgents, null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 700.dp
                Column {
                    if (!narrow) { AgentHeader(detailsWidth); RowDivider() }
                    catalog.agents.forEachIndexed { index, agent ->
                        if (index > 0) RowDivider()
                        AgentRow(model, agent, narrow, agent.id in ready, agent.id in expanded, scope, detailsWidth) {
                            expanded = if (agent.id in expanded) expanded - agent.id else expanded + agent.id
                        }
                    }
                    if (catalog.total == 0) Column(
                        Modifier.fillMaxWidth().padding(20.dp).testTag("settings-agents-empty"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Note(strings.placeAssistantsNoMatch)
                        CompactOutlinedButton(onClick = { query = ""; filter = AgentCatalogFilter.ALL; page = 1 }) {
                            ButtonLabel(strings.libResetFilters)
                        }
                    }
                }
            }
        }
        if (catalog.pages > 1) ControlRow(Modifier.testTag("settings-agents-pagination")) {
            Note(strings.setAgentPage(catalog.page, catalog.pages))
            CompactOutlinedButton(
                onClick = { page = catalog.page - 1 }, enabled = catalog.page > 1,
                modifier = Modifier.testTag("settings-agents-previous"),
            ) { ButtonLabel(strings.setAgentPrevious) }
            (1..catalog.pages).forEach { number ->
                CompactOutlinedButton(
                    onClick = { page = number },
                    contentColor = if (number == catalog.page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-agents-page-$number").semantics { selected = number == catalog.page },
                ) { ButtonLabel(number.toString()) }
            }
            CompactOutlinedButton(
                onClick = { page = catalog.page + 1 }, enabled = catalog.page < catalog.pages,
                modifier = Modifier.testTag("settings-agents-next"),
            ) { ButtonLabel(strings.setAgentNext) }
        }
        Note(strings.setAgentsFoot)
        CompactOutlinedButton(onClick = { manual = !manual }, modifier = Modifier.testTag("settings-manual-toggle")) {
            ButtonLabel("${if (manual) "▾" else "▸"} ${strings.setAgentManual}")
        }
        if (manual) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.launch?.let { launch ->
                Note(strings.connectManualHint)
                PathChip(launch.shellCommand())
                FlashButton(strings.setCopy, strings.setCopied, onClick = { copyToClipboard(launch.shellCommand()) })
            }
            if (model.mcpServing?.thisBuild == false) CompactOutlinedButton(
                onClick = { scope.launch { model.serveAgentsFromThisBuild() } },
                modifier = Modifier.testTag("settings-mcp-serve-here"),
            ) { ButtonLabel(strings.mcpServeFromThisBuild) }
        }
    }
}

@Composable
private fun AgentHeader(detailsWidth: Dp) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.setColAgent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        HeaderHint(strings.setColVisible, strings.setVisibilityHint, Modifier.width(VisibilityWidth))
        HeaderHint(strings.setColAssistance, strings.setAssistanceHint, Modifier.width(AssistanceWidth))
        HeaderHint(strings.setColMcp, strings.setMcpHint, Modifier.width(IntegrationWidth))
        Box(Modifier.width(detailsWidth))
    }
}

@Composable
private fun HeaderHint(label: String, hint: String, modifier: Modifier) {
    RuleblendTooltip(hint, modifier.semantics { contentDescription = "$label. $hint" }) {
        Text("$label ⓘ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentRow(model: SettingsModel, agent: AgentAdapter, narrow: Boolean, ready: Boolean, expanded: Boolean, operationScope: CoroutineScope, detailsWidth: Dp, onExpand: () -> Unit) {
    val strings = LocalStrings.current
    Column(Modifier.fillMaxWidth().testTag("settings-agent-${agent.id}")) {
        if (narrow) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AgentIdentity(model, agent)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AgentVisibility(model, agent, showLabel = true)
                AgentAssistance(model, agent, ready, showLabel = true)
            }
            ControlRow {
                IntegrationSummary(model, agent, Modifier.weight(1f))
                AgentDisclosure(agent.id, expanded, onExpand, Modifier.width(detailsWidth))
            }
        } else Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentIdentity(model, agent, Modifier.weight(1f))
            AgentVisibility(model, agent, modifier = Modifier.width(VisibilityWidth))
            AgentAssistance(model, agent, ready, modifier = Modifier.width(AssistanceWidth))
            IntegrationSummary(model, agent, Modifier.width(IntegrationWidth))
            AgentDisclosure(agent.id, expanded, onExpand, Modifier.width(detailsWidth))
        }
        if (expanded) {
            RowDivider()
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)
                    .testTag("settings-agent-details-${agent.id}"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(strings.setColCanManage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val capabilities = agent.capabilities()
                    Capability(strings.setAgentRulesCapability, capabilities.rules, "rules")
                    Capability(strings.setAgentMcpCapability, capabilities.mcp, "mcp")
                    Capability(strings.setAgentSkillsCapability, capabilities.skills, "skills")
                    Capability(strings.setAgentSubagentsCapability, capabilities.subagents, "subagents")
                }
                Text(strings.setColMcp, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                ControlRow { Connection(model, agent, operationScope) }
                if (agent.cli != null) {
                    Text(strings.setAgentLaunch, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Note(strings.setCliArgumentsHint)
                    CliArguments(model, agent)
                }
            }
        }
    }
}

@Composable
private fun AgentIdentity(model: SettingsModel, agent: AgentAdapter, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(agent.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (agent.id in model.detectedAgentIds) Text(
            agent.globalFile().abbreviateHome(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ) else Note(LocalStrings.current.setAgentNotDetected)
    }
}

@Composable
private fun AgentVisibility(model: SettingsModel, agent: AgentAdapter, showLabel: Boolean = false, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    RuleblendTooltip(strings.setVisibilityHint, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showLabel) Note(strings.setColVisible)
            MiniSwitch(
                checked = agent.id !in model.hiddenAgents,
                onCheckedChange = { model.setAgentHidden(agent, !it) },
                modifier = Modifier.testTag("settings-visible-${agent.id}").semantics { contentDescription = "${agent.name}: ${strings.setColVisible}" },
            )
        }
    }
}

@Composable
private fun AgentAssistance(model: SettingsModel, agent: AgentAdapter, ready: Boolean, showLabel: Boolean = false, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    RuleblendTooltip(strings.setAssistanceHint, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showLabel) Note(strings.setColAssistance)
            MiniSwitch(
                checked = agent.id in model.assistanceAssistants,
                onCheckedChange = { model.setAssistanceAssistant(agent.id, it) },
                enabled = ready,
                modifier = Modifier.testTag("settings-assistance-${agent.id}").semantics { contentDescription = "${agent.name}: ${strings.setColAssistance}" },
            )
        }
    }
}

@Composable
private fun AgentDisclosure(id: String, expanded: Boolean, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    CompactOutlinedButton(onClick = onExpand, modifier = modifier.testTag("settings-details-$id"), contentPadding = DetailsPadding) {
        Text(
            if (expanded) strings.setAgentCollapse else strings.setAgentDetails,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false,
        )
    }
}

private fun integrationStatus(model: SettingsModel, id: String): ConnectState? = model.connectStates[id] ?: when (model.skillStates[id]) {
    SkillStatus.INSTALLED -> ConnectState.CONNECTED
    SkillStatus.OUTDATED -> ConnectState.SKILL_OUTDATED
    SkillStatus.FOREIGN -> ConnectState.SKILL_FOREIGN
    SkillStatus.NOT_INSTALLED -> ConnectState.NOT_CONNECTED
    null -> null
}

@Composable
private fun IntegrationSummary(model: SettingsModel, agent: AgentAdapter, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val state = integrationStatus(model, agent.id)
    val color = when (state) {
        ConnectState.CONNECTED -> RuleblendTheme.extraColors.success
        ConnectState.SKILL_FOREIGN, ConnectState.MCP_FOREIGN -> RuleblendTheme.extraColors.danger
        ConnectState.STALE, ConnectState.SKILL_OUTDATED, ConnectState.MCP_OUTDATED -> RuleblendTheme.extraColors.warning
        else -> RuleblendTheme.extraColors.faint
    }
    val textColor = when (state) {
        ConnectState.SKILL_FOREIGN, ConnectState.MCP_FOREIGN, ConnectState.STALE,
        ConnectState.SKILL_OUTDATED, ConnectState.MCP_OUTDATED -> color
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fullLabel = state?.label(strings) ?: strings.setConnManual
    val label = if (state == ConnectState.SKILL_FOREIGN || state == ConnectState.MCP_FOREIGN) strings.setAgentConflict else fullLabel
    RuleblendTooltip(fullLabel, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = textColor)
        }
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
    val currentText by rememberUpdatedState(text)
    DisposableEffect(model, agent.id) {
        onDispose {
            if (currentText != model.agentCliArguments[agent.id].orEmpty()) model.changeAgentCliArguments(agent.id, currentText)
        }
    }
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
private fun Connection(model: SettingsModel, agent: AgentAdapter, operationScope: CoroutineScope) {
    val strings = LocalStrings.current
    val extra = RuleblendTheme.extraColors
    val state = integrationStatus(model, agent.id)
    // No connector on this machine: the agent is wired by hand with the command below the table.
    if (state == null) {
        Note(strings.setConnManual)
        return
    }
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
    model.connectors.find { it.agentId == agent.id }?.let { ConnectActions(model, it, state, operationScope) }
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
private fun ConnectActions(model: SettingsModel, connector: McpConnector, state: ConnectState, scope: CoroutineScope) {
    val strings = LocalStrings.current
    // The catalog owns this scope: closing details must not cancel registration or its state refresh.
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
