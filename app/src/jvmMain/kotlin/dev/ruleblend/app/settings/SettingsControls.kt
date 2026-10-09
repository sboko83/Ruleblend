package dev.ruleblend.app.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SectionHeaderStyle
import kotlinx.coroutines.delay

private val LabelWidth = 240.dp

@Composable
internal fun SettingsSectionBlock(
    section: SettingsSection,
    title: String,
    summary: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title.uppercase(), style = SectionHeaderStyle)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            Modifier.fillMaxWidth().testTag("settings-panel-${section.name.lowercase()}"),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(content = content)
        }
    }
}

/** One "label + hint | control" row; in a narrow window the control moves under the label. */
@Composable
internal fun SettingsRow(
    label: String,
    hint: String?,
    narrow: Boolean,
    control: @Composable ColumnScope.() -> Unit,
) {
    val labelBlock = @Composable {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
    if (narrow) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            labelBlock()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = control)
        }
        return
    }
    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.width(LabelWidth)) { labelBlock() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = control)
    }
}

/** The design's `.sctl`: controls in a row that wraps instead of overflowing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlRow(
    modifier: Modifier = Modifier,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = alignment,
        content = content,
    )
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline)

@Composable
internal fun Note(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) = Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)

@Composable
internal fun ButtonLabel(text: String) = Text(text, style = MaterialTheme.typography.labelMedium)

@Composable
internal fun FieldLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.SemiBold,
    color = RuleblendTheme.extraColors.faint,
)

@Composable
internal fun Foot(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = RuleblendTheme.extraColors.faint,
    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
)

@Composable
internal fun PathChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * An action whose whole result is that it happened: the button says so for a moment instead of
 * opening a dialog to be dismissed.
 */
@Composable
internal fun FlashButton(label: String, done: String, onClick: () -> Unit, tag: String? = null) {
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flashing) {
        if (flashing) {
            delay(FlashMillis)
            flashing = false
        }
    }
    CompactOutlinedButton(
        enabled = !flashing,
        onClick = {
            onClick()
            flashing = true
        },
        modifier = Modifier.testTag(tag ?: "settings-flash-$label"),
    ) {
        ButtonLabel(if (flashing) done else label)
    }
}

private const val FlashMillis = 900L
