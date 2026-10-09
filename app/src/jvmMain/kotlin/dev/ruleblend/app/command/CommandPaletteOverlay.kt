package dev.ruleblend.app.command

import dev.ruleblend.app.library.KindMark

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.Pill
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.ruleblendFieldColors

private val PaletteShape = RoundedCornerShape(6.dp)

/**
 * The ⌘K surface: one input, a ranked list, and nothing else. It is drawn over the whole window —
 * rail included — because it is the one navigation that does not belong to a surface.
 *
 * Row selection is keyboard-first (arrows and Enter), so the input keeps focus the entire time and
 * intercepts the navigation keys before the field consumes them as text editing.
 */
@Composable
fun CommandPalette(
    items: List<CommandItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (CommandItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var highlighted by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    // The list is rebuilt on every keystroke: a highlight kept from the previous list would point at
    // a row the user never saw.
    val active = highlighted.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag("command-scrim"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = PaletteShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(top = 96.dp)
                .width(520.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .testTag("command-palette"),
        ) {
            Column {
                RuleblendOutlinedTextField(
                    value = query,
                    onValueChange = {
                        highlighted = 0
                        onQueryChange(it)
                    },
                    placeholder = { Text(strings.commandPlaceholder) },
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (items.isNotEmpty()) highlighted = (active + 1) % items.size
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (items.isNotEmpty()) highlighted = (active - 1 + items.size) % items.size
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    items.getOrNull(active)?.let(onSelect)
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        }
                        .testTag("command-input"),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (items.isEmpty()) {
                    Text(
                        text = strings.commandNoMatches,
                        style = MaterialTheme.typography.bodySmall,
                        color = RuleblendTheme.extraColors.faint,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        items.forEachIndexed { index, item ->
                            CommandRow(item, index == active, onClick = { onSelect(item) })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    text = strings.commandScopeHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = RuleblendTheme.extraColors.faint,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CommandRow(item: CommandItem, highlighted: Boolean, onClick: () -> Unit) {
    val extras = RuleblendTheme.extraColors
    val (background, foreground) = when (item.kind) {
        CommandKind.RULE -> extras.accentBackground to MaterialTheme.colorScheme.primary
        CommandKind.SUBAGENT -> extras.subagentBackground to extras.subagent
        CommandKind.SKILL -> extras.successBackground to extras.success
        CommandKind.MCP -> extras.infoBackground to extras.info
        CommandKind.PLACE -> extras.warningBackground to extras.warning
        CommandKind.GROUP, CommandKind.PROFILE, CommandKind.ACTION ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (highlighted) extras.accentBackground else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("command-item-${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val libraryTarget = item.target as? CommandTarget.Library
        if (libraryTarget != null) KindMark(libraryTarget.key.kind)
        else Pill(item.kind.label(LocalStrings.current), background, foreground)
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = extras.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

fun CommandKind.label(strings: Strings): String = when (this) {
    CommandKind.ACTION -> strings.commandTagAction
    CommandKind.PLACE -> strings.commandTagPlace
    CommandKind.RULE -> strings.commandTagRule
    CommandKind.SUBAGENT -> strings.commandTagSubagent
    CommandKind.SKILL -> strings.commandTagSkill
    CommandKind.MCP -> strings.commandTagMcp
    CommandKind.GROUP -> strings.commandTagGroup
    CommandKind.PROFILE -> strings.commandTagProfile
}
