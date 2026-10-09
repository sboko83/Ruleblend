package dev.ruleblend.app.place

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.integration.ProjectAssistant
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.FieldClearButton
import dev.ruleblend.app.theme.RoomyButtonPadding
import dev.ruleblend.app.theme.FieldLeadingIconSize
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.integration.ProjectTarget
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Project-local assistant switches. The set is picked once per project and then only read, so it
 * folds to one summary line instead of standing between the project name and its files. Switches
 * persist the assistant set without touching files already on disk.
 */
@Composable
internal fun ProjectAssistants(model: IntegrationModel, target: ProjectTarget) {
    val assistants = model.projectAssistants(target)
    if (assistants.isEmpty()) return
    // Keyed on the folder: a switch rebuilds the target (its agent list changes) and must not fold the panel.
    var expanded by remember(target.dir) { mutableStateOf(false) }
    val connected = assistants.count { it.enabled }
    if (expanded) {
        AssistantsPanel(model, target, assistants, connected, onCollapse = { expanded = false })
    } else {
        AssistantsSummary(assistants.size, connected, onExpand = { expanded = true })
    }
}

@Composable
private fun AssistantsSummary(total: Int, connected: Int, onExpand: () -> Unit) {
    val strings = LocalStrings.current
    val outline = MaterialTheme.colorScheme.outline
    val extras = RuleblendTheme.extraColors
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.surfaceCornerRadius)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .clip(shape)
                .border(1.dp, outline, shape)
                .clickable(role = Role.Button, onClick = onExpand)
                .testTag("place-assistants-summary")
                .padding(RoomyButtonPadding),
        ) {
            StatusDot(if (connected > 0) extras.success else extras.unmanaged)
            Text(strings.placeAssistants, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(1.dp).fillMaxHeight().background(outline))
            Text(
                strings.placeAssistantsConnected(connected, total),
                style = MaterialTheme.typography.labelMedium,
                color = extras.faint,
            )
            Chevron(pointsUp = false, color = MaterialTheme.colorScheme.onSurface)
        }
        ActionButton(strings.placeAssistantsConfigure, onExpand, Modifier.testTag("place-assistants-configure"))
    }
}

@Composable
private fun AssistantsPanel(
    model: IntegrationModel,
    target: ProjectTarget,
    assistants: List<ProjectAssistant>,
    connected: Int,
    onCollapse: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val extras = RuleblendTheme.extraColors
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.surfaceCornerRadius)
    var query by remember(target.dir) { mutableStateOf("") }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(14.dp)
            .testTag("place-assistants-panel"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.placeAssistants,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                strings.placeAssistantsConnected(connected, assistants.size),
                style = MaterialTheme.typography.labelMedium,
                color = extras.faint,
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedButton(
                onClick = onCollapse,
                contentPadding = RoomyButtonPadding,
                modifier = Modifier.testTag("place-assistants-collapse"),
            ) {
                Text(strings.placeAssistantsCollapse, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                Chevron(pointsUp = true)
            }
        }
        RuleblendOutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(strings.placeAssistantsSearch) },
            singleLine = true,
            colors = ruleblendFieldColors(),
            // As tall as the Collapse button beside it; the glass sits on the margin the text keeps.
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            leadingIcon = {
                Icon(
                    painterResource(RuleblendTheme.icons.search),
                    contentDescription = null,
                    tint = RuleblendTheme.extraColors.faint,
                    modifier = Modifier.size(FieldLeadingIconSize),
                )
            },
            trailingIcon = if (query.isEmpty()) null else {
                { FieldClearButton(strings.placeAssistantsSearchClear) { query = "" } }
            },
            modifier = Modifier.fillMaxWidth().testTag("place-assistants-search"),
        )
        val needle = query.trim()
        val shown = assistants.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
        if (shown.isEmpty()) {
            Text(strings.placeAssistantsNoMatch, style = MaterialTheme.typography.bodySmall, color = extras.faint)
        } else {
            AssistantGrid(shown) { assistant ->
                scope.launch { model.setProjectAssistantEnabled(target, assistant.id, !assistant.enabled) }
            }
        }
    }
}

/**
 * Filled column by column, so the order reads down the first column before the second — the way a
 * sorted list is scanned. As many columns as the width holds, never more than four.
 */
@Composable
private fun AssistantGrid(assistants: List<ProjectAssistant>, onToggle: (ProjectAssistant) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / AssistantCellMinWidth).toInt().coerceIn(1, MaxAssistantColumns)
        val rows = (assistants.size + columns - 1) / columns
        val filled = assistants.chunked(rows)
        Row(Modifier.fillMaxWidth()) {
            filled.forEach { column ->
                Column(Modifier.weight(1f)) {
                    column.forEach { assistant -> AssistantCell(assistant, onToggle) }
                }
            }
            repeat(columns - filled.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** The whole line is the switch; the box inside only shows its state. */
@Composable
private fun AssistantCell(assistant: ProjectAssistant, onToggle: (ProjectAssistant) -> Unit) {
    val extras = RuleblendTheme.extraColors
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RuleblendTheme.dimensions.cardCornerRadius))
            .toggleable(value = assistant.enabled, role = Role.Checkbox, onValueChange = { onToggle(assistant) })
            .testTag("place-assistant-${assistant.id}")
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        StatusDot(if (assistant.enabled) extras.success else extras.unmanaged)
        SquareCheckbox(assistant.enabled, onCheckedChange = null)
        Text(
            assistant.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Drawn rather than typed, like the field's clear mark: a glyph's line box would sit it off-centre. */
@Composable
private fun Chevron(pointsUp: Boolean, color: Color = LocalContentColor.current) {
    Canvas(Modifier.size(ChevronSize).rotate(if (pointsUp) -90f else 0f)) {
        val stroke = ChevronStroke.toPx()
        val tip = Offset(size.width * 0.7f, size.height / 2)
        drawLine(color, Offset(size.width * 0.35f, 0f), tip, stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.35f, size.height), tip, stroke, StrokeCap.Round)
    }
}

private val AssistantCellMinWidth = 180.dp
private const val MaxAssistantColumns = 4
private val ChevronSize = 10.dp
private val ChevronStroke = 1.5.dp
