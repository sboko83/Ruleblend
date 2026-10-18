package dev.ruleblend.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.ToggleChip
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.integration.SubagentAgentFields
import dev.ruleblend.core.integration.SubagentFieldKind
import dev.ruleblend.core.integration.SubagentFieldSpec
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.SUBAGENT_FIELD_MODEL
import dev.ruleblend.core.model.SUBAGENT_FIELD_TOOLS
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.model.withVariant

private val ListWidth = 220.dp
private val SectionShape = RoundedCornerShape(10.dp)
private val RowShape = RoundedCornerShape(7.dp)

/**
 * Per-assistant fields of one subagent. The definition itself stays portable; only these values are
 * asked for separately, because a model name or a tool list valid in one assistant rarely is in
 * another. An assistant left blank is installed without those fields rather than with a guess.
 *
 * The list on the left holds only the assistants that have values or a copy installed, so the
 * section grows with what the user tuned rather than with how many assistants Ruleblend supports;
 * the rest are one pick away. The right side edits one assistant at a time.
 */
@Composable
internal fun SubagentVariantsForm(model: LibraryModel, draft: Block, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val agents = model.subagentAgents
    if (agents.isEmpty()) return
    val readOnly = draft.source != null
    val installed = model.usage.agentsOf(LibraryObjectKey(LibraryObjectKind.SUBAGENT, draft.id))
    // An imported definition opens on the assistant it came from: that is where its values live.
    var picked by remember(draft.id) { mutableStateOf(draft.source?.assistant) }
    // The picked assistant stays listed while it is still empty, so adding one does not make it vanish
    // before the first value is typed; once the user moves on, an empty one drops out again.
    val tuned = agents.filter { !draft.variant(it.agentId).isEmpty || it.agentId == picked && it.agentId !in installed }
    val defaults = agents.filter { it !in tuned && it.agentId in installed }
    val listed = tuned + defaults
    val selected = listed.firstOrNull { it.agentId == picked } ?: listed.firstOrNull()
    val rest = agents.filter { it !in listed }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            FieldLabel(strings.libSubagentVariants)
            Text(
                strings.libSubagentVariantsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .border(1.dp, MaterialTheme.colorScheme.outline, SectionShape),
        ) {
            Column(
                Modifier.width(ListWidth).fillMaxHeight().padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (tuned.isNotEmpty()) GroupCaption(strings.libSubagentTuned)
                tuned.forEach { agent ->
                    AgentRow(agent, draft.variant(agent.agentId), agent == selected, agent.agentId in installed) {
                        picked = agent.agentId
                    }
                }
                if (defaults.isNotEmpty()) GroupCaption(strings.libSubagentInstalledDefaults)
                defaults.forEach { agent ->
                    AgentRow(agent, SubagentVariant.EMPTY, agent == selected, installed = true) { picked = agent.agentId }
                }
                Spacer(Modifier.weight(1f))
                if (!readOnly && rest.isNotEmpty()) AddAgentButton(rest) { picked = it }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            Box(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (selected == null) {
                    Text(
                        strings.libSubagentPickAgent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    AgentFields(model, draft, selected, selected.agentId in installed, readOnly) {
                        // Reset keeps the assistant open on its now-empty fields instead of letting
                        // the list drop it under the user's cursor.
                        picked = selected.agentId
                        model.editDraft { it.withVariant(selected.agentId, SubagentVariant.EMPTY) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCaption(text: String) = Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    color = RuleblendTheme.extraColors.faint,
    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
)

/** One assistant in the list: its name, whether a copy is installed, and what it overrides. */
@Composable
private fun AgentRow(
    agent: SubagentAgentFields,
    variant: SubagentVariant,
    selected: Boolean,
    installed: Boolean,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    Column(
        Modifier.fillMaxWidth().clip(RowShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(subagentAgentTag(agent.agentId)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                agent.agentName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (variant.isEmpty) FontWeight.Normal else FontWeight.SemiBold,
                color = if (variant.isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (installed) StatusDot(RuleblendTheme.extraColors.success)
        }
        Text(
            variant.summary().ifEmpty { strings.libSubagentAgentDefaults },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Field ids are the keys the assistants' own files use, so they read the same as the file would. */
private fun SubagentVariant.summary(): String = fields.entries.joinToString(" · ") { (key, value) -> "$key $value" }

@Composable
private fun AddAgentButton(rest: List<SubagentAgentFields>, onPick: (String) -> Unit) {
    val strings = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        CompactOutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(SUBAGENT_AGENT_ADD_TAG)) {
            Text(strings.libSubagentAddAgent(rest.size))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            rest.forEach { agent ->
                DropdownMenuItem(
                    text = { Text(agent.agentName) },
                    onClick = {
                        open = false
                        onPick(agent.agentId)
                    },
                    modifier = Modifier.testTag("$SUBAGENT_AGENT_ADD_TAG-${agent.agentId}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentFields(
    model: LibraryModel,
    draft: Block,
    agent: SubagentAgentFields,
    installed: Boolean,
    readOnly: Boolean,
    onReset: () -> Unit,
) {
    val strings = LocalStrings.current
    val variant = draft.variant(agent.agentId)
    val declared = agent.fields.map { it.id }.toSet()
    // A field the assistant's file carried but Ruleblend does not model stays editable under its
    // own name, so adopting a foreign definition never hides part of it.
    val specs = agent.fields + variant.fields.keys.filter { it !in declared }.map { SubagentFieldSpec(it) }
    fun set(fieldId: String, value: String) = model.editDraft { block ->
        block.withVariant(agent.agentId, block.variant(agent.agentId).with(fieldId, value))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(agent.agentName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (installed) {
                Text(
                    strings.libSubagentAgentInstalled,
                    style = MaterialTheme.typography.labelSmall,
                    color = RuleblendTheme.extraColors.success,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!readOnly && !variant.isEmpty) {
                CompactOutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.testTag(SUBAGENT_AGENT_RESET_TAG),
                ) { Text(strings.libSubagentReset) }
            }
        }
        if (variant.isEmpty) {
            Text(
                strings.libSubagentNothingSet(agent.agentName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        specs.forEach { spec ->
            val value = variant[spec.id].orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.label(strings), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RuleblendOutlinedTextField(
                    value = value,
                    readOnly = readOnly,
                    enabled = !readOnly,
                    onValueChange = { set(spec.id, it) },
                    placeholder = { Text(spec.hint(strings)) },
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.fillMaxWidth().testTag(subagentFieldTag(agent.agentId, spec.id)),
                )
                if (!readOnly && spec.suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        spec.suggestions.forEach { suggestion ->
                            ToggleChip(
                                suggestion,
                                on = value == suggestion,
                                onClick = { set(spec.id, suggestion) },
                                textStyle = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Test tag of one per-assistant field: which assistant it tunes and which of its fields. */
internal fun subagentFieldTag(agentId: String, fieldId: String): String = "subagent-field-$agentId-$fieldId"

/** Test tag of an assistant's row in the per-assistant list. */
internal fun subagentAgentTag(agentId: String): String = "subagent-agent-$agentId"

/** Opens the menu of assistants not listed yet; each item is tagged with this plus `-<agent id>`. */
internal const val SUBAGENT_AGENT_ADD_TAG = "subagent-agent-add"

internal const val SUBAGENT_AGENT_RESET_TAG = "subagent-agent-reset"

private fun SubagentFieldSpec.label(strings: Strings): String = when (id) {
    SUBAGENT_FIELD_MODEL -> strings.libSubagentModel
    SUBAGENT_FIELD_TOOLS -> strings.libSubagentFieldTools
    "color" -> strings.libSubagentFieldColor
    else -> strings.libSubagentFieldCustom(id)
}

/** The shape the agent expects, shown inside the empty field since the name now sits above it. */
private fun SubagentFieldSpec.hint(strings: Strings): String = when {
    id == SUBAGENT_FIELD_MODEL -> strings.libSubagentModelHint
    id == SUBAGENT_FIELD_TOOLS || kind == SubagentFieldKind.LIST -> strings.libSubagentFieldToolsHint
    else -> strings.libSubagentFieldUnset
}
