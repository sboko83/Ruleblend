package dev.ruleblend.app.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.takeOrElse
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Square check mark — used instead of Material's round Checkbox. A `null` [onCheckedChange] draws the
 * mark only, for a row that is itself the toggle and wants the whole line as its hit target.
 */
@Composable
fun SquareCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val border = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (checked) accent else Color.Transparent)
            .border(1.5.dp, if (checked) accent else border, RoundedCornerShape(3.dp))
            // Toggleable, not clickable: screen readers and tests read the role and checked state.
            .then(
                if (onCheckedChange == null) Modifier
                else Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

/** `.pill` from the design mockups — `padding: 4px 12px` on a full-round shape. */
private val PillPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
private val PillShape = RoundedCornerShape(50)

/** A pill that is a move of its own on a header row, not a chip among many: more air, same shape. */
val RoomyPillPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)

/** Pill toggle chip, used for the per-project agent toggles. */
@Composable
fun ToggleChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    contentPadding: PaddingValues = PillPadding,
) {
    val border = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val bg = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        label,
        style = textStyle,
        color = fg,
        modifier = modifier
            .clip(PillShape)
            .background(bg)
            .border(1.dp, border, PillShape)
            .clickable(onClick = onClick)
            .padding(contentPadding),
    )
}

/**
 * Outline pill button in the [ToggleChip] shape, for inline actions that should read as chips, not
 * Material buttons. Same corner radius, border and padding as a chip's off state.
 */
@Composable
fun CompactPillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PillPadding,
    content: @Composable () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val contentColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .clip(PillShape)
            .border(1.dp, if (enabled) outline else outline.copy(alpha = DisabledContentAlpha), PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                if (enabled) contentColor else contentColor.copy(alpha = DisabledContentAlpha),
            content = content,
        )
    }
}

/** Small filled status dot, used where one object has one state. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(StatusDotSize).clip(StatusDotShape).background(color))
}

/**
 * The dot of something that sums several objects up: the leading mark fills the left half and the
 * next one the right, so two states are read at a glance and the rest stay in the tooltip. One mark
 * paints the whole circle; no marks at all means nothing of Ruleblend's is in there, which is the
 * grey of [StatusMark.UNMANAGED] rather than the green of "nothing is out of sync".
 */
@Composable
fun StatusDot(marks: Set<StatusMark>, modifier: Modifier = Modifier) {
    val leading = marks.leading()
    if (leading.size < 2) {
        StatusDot((leading.firstOrNull() ?: StatusMark.UNMANAGED).color(), modifier)
        return
    }
    Row(modifier.size(StatusDotSize).clip(StatusDotShape)) {
        leading.forEach { mark ->
            Box(Modifier.weight(1f).fillMaxHeight().background(mark.color()))
        }
    }
}

private val StatusDotSize = 12.dp
private val StatusDotShape = RoundedCornerShape(50)

/**
 * The "x" that empties a text field. Sized here rather than left to Material: its trailing slot is a
 * 48 dp square with the icon centred in it, which parks the mark far off the border while the text
 * starts at the field's own padding. [RuleblendOutlinedTextField] lays this out on that same margin.
 *
 * The mark is drawn rather than typed: a "\u00d7" glyph is centred by its line box, and the line box
 * carries the font's ascent and descent, so the ink lands a couple of dp below the middle of the hit
 * box — visibly low beside a field only 36 dp tall. Two strokes have no metrics to be off by.
 */
@Composable
fun FieldClearButton(description: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    RuleblendTooltip(description) {
        Box(
            Modifier
                .size(FieldClearSize)
                .clip(StatusDotShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(FieldClearMarkSize)) {
                val stroke = FieldClearMarkStroke.toPx()
                drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), stroke, StrokeCap.Round)
            }
        }
    }
}

/**
 * The "x" that closes a dialog, sitting in the top-right corner of every non-main surface. It is the
 * same two drawn strokes as [FieldClearButton] — a glyph would sit low in its line box — but at the
 * scale of a control the eye finds without looking for it: a field's clear mark is a hint inside the
 * field, this one is the way out of a form.
 */
@Composable
fun DialogCloseButton(description: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    RuleblendTooltip(description) {
        Box(
            Modifier
                .size(DialogCloseSize)
                .clip(StatusDotShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(DialogCloseMarkSize)) {
                val stroke = DialogCloseMarkStroke.toPx()
                drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
                drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), stroke, StrokeCap.Round)
            }
        }
    }
}

/** Twice the ink of a field's clear mark, in a hit box a pointer lands on without aiming. */
val DialogCloseSize = 24.dp
private val DialogCloseMarkSize = 11.dp
private val DialogCloseMarkStroke = 1.6.dp

internal val FieldClearSize = 16.dp

/** Width of the picture a field opens with; the text starts past it. */
val FieldLeadingIconSize = 16.dp

/** The ink of the mark inside [FieldClearSize]; matches the glyph it replaces. */
private val FieldClearMarkSize = 6.dp
private val FieldClearMarkStroke = 1.5.dp

private val CompactButtonShape = RoundedCornerShape(6.dp)
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/**
 * Padding of a button that is the move itself rather than a control around it — the writes on an
 * object and on a file. The label stays the compact one; the air around it is what makes the button
 * a target the eye finds before it reads the word.
 */
val RoomyButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

/** Keeps a lone glyph ("−", "+") from collapsing into a sliver; long labels are free to grow. */
private val CompactButtonMinWidth = 24.dp

/**
 * The shared body of the compact buttons. Material's own [Button] cannot be shrunk to the design's
 * `padding: 4px 10px`: its content row carries a 58 x 40 dp `defaultMinSize` and its surface a 48 dp
 * minimum interactive size, and `contentPadding` overrides neither. Building on [Surface] directly
 * keeps the hover and press feedback while letting the button hug its label.
 */
@Composable
private fun CompactButtonSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = CompactButtonShape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
        ) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                Row(
                    Modifier.defaultMinSize(minWidth = CompactButtonMinWidth).padding(contentPadding),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

/** Compact filled button — much smaller than Material's default. */
@Composable
fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = CompactButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    CompactButtonSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = null,
        contentPadding = contentPadding,
        content = content,
    )
}

/** Compact outlined button, also used for the destructive variant. */
@Composable
fun CompactOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = CompactButtonPadding,
    content: @Composable RowScope.() -> Unit,
) {
    CompactButtonSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color.Transparent,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = DisabledContentAlpha),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * The design's `.btn.ghost` — same metrics as the other compact buttons, no border and no fill, for
 * inline actions that must not compete with the content they sit next to.
 */
@Composable
fun CompactGhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    CompactButtonSurface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = Color.Transparent,
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = DisabledContentAlpha),
        border = null,
        contentPadding = CompactButtonPadding,
        content = content,
    )
}

/**
 * An icon and its label inside a compact button. The icon takes the button's content colour, so a
 * filled, outlined and ghost button all tint it the way they tint their text.
 */
@Composable
fun ButtonLabel(text: String, icon: DrawableResource? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(ButtonIconSize))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private val ButtonIconSize = 16.dp

/**
 * A move reduced to its picture, square and as tall as a [RoomyButtonPadding] button beside it. The
 * word it no longer shows is its tooltip and its accessible name, so a pointer and a screen reader
 * both still get the label. [colors] fills it; `null` leaves it outlined in [contentColor].
 */
@Composable
fun IconActionButton(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = IconActionButtonSize,
    enabled: Boolean = true,
) {
    val sized = modifier.size(size).semantics { contentDescription = label }
    val glyph: @Composable RowScope.() -> Unit = {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonIconSize))
    }
    RuleblendTooltip(label) {
        if (colors != null) {
            CompactButton(onClick = onClick, enabled = enabled, modifier = sized, colors = colors, contentPadding = PaddingValues(0.dp), content = glyph)
        } else {
            CompactOutlinedButton(
                onClick = onClick,
                enabled = enabled,
                modifier = sized,
                contentColor = contentColor,
                contentPadding = PaddingValues(0.dp),
                content = glyph,
            )
        }
    }
}

val IconActionButtonSize = 36.dp

/** Material's own disabled content opacity — the outlined and ghost buttons mix their own colour. */
private const val DisabledContentAlpha = 0.38f

/**
 * Hover tooltip styled like the rest of the app (Material's default is a Material-you pill that
 * clashes with the app's flat look). Shows nothing when [text] is blank.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleblendTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isBlank()) {
        content()
        return
    }
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 4.dp,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        },
        delayMillis = 400,
        modifier = modifier,
        content = content,
    )
}

/**
 * Hover tooltip with a rich body, for content that does not fit a single line (e.g. a block of
 * markdown). The [tooltip] lambda renders inside the app-styled surface; [content] is the trigger.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleblendTooltipRich(
    modifier: Modifier = Modifier,
    tooltip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 4.dp,
            ) {
                Box(Modifier.widthIn(max = 460.dp).heightIn(max = 320.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    tooltip()
                }
            }
        },
        delayMillis = 400,
        modifier = modifier,
        content = content,
    )
}

/** Half of Material3's default 16.dp text field content padding — the app's text is compact, the fields shouldn't dwarf it. */
val RuleblendFieldContentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

/** Material3 hardcodes a 56.dp min height regardless of [RuleblendFieldContentPadding] — override it so compact padding actually shrinks the field. */
private val RuleblendFieldMinHeight = 36.dp

/** Filled-box colors for editor text fields. */
@Composable
fun ruleblendFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.outline,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)

/**
 * Same look as Material's [androidx.compose.material3.OutlinedTextField], but with [contentPadding]
 * exposed — the public overload hardcodes 16.dp on every side, which dwarfs this app's compact text.
 */
@Composable
fun RuleblendOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    contentPadding: PaddingValues = RuleblendFieldContentPadding,
    placeholder: (@Composable () -> Unit)? = null,
    /**
     * Sits inside the field, before the text: what the field is for, such as a search glass. Laid
     * out like [trailingIcon] and for the same reason; the slot is [FieldLeadingIconSize] wide.
     */
    leadingIcon: (@Composable () -> Unit)? = null,
    /** Sits inside the field, at its end: an action on the text itself, such as clearing it. */
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val layoutDirection = LocalLayoutDirection.current
    // The action is laid out over the container rather than in Material's trailing slot, which
    // reserves a 48 dp square and centres the icon in it. Here it sits on the field's own end
    // padding — the margin the text is written from — and the text gives up just enough room for it.
    val startInset = contentPadding.calculateStartPadding(layoutDirection)
    val endInset = contentPadding.calculateEndPadding(layoutDirection)
    val textPadding = PaddingValues(
        start = if (leadingIcon == null) startInset else startInset + FieldLeadingIconSize + 8.dp,
        top = contentPadding.calculateTopPadding(),
        end = if (trailingIcon == null) endInset else endInset + FieldClearSize + 4.dp,
        bottom = contentPadding.calculateBottomPadding(),
    )
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val textColor = textStyle.color.takeOrElse {
        when {
            !enabled -> colors.disabledTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }
    }
    val mergedTextStyle = textStyle.merge(TextStyle(color = textColor))

    // Keep selection and scroll in the same editor state. The legacy String overload can
    // move the viewport on focus between a mouse press and the ensuing selection drag.
    val state = rememberTextFieldState(value, TextRange.Zero)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    SideEffect {
        if (state.text.toString() != value) {
            state.edit { replace(0, length, value) }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != currentValue) currentOnValueChange(text)
        }
    }

    BasicTextField(
        state = state,
        modifier = modifier.defaultMinSize(
            minWidth = OutlinedTextFieldDefaults.MinWidth,
            minHeight = RuleblendFieldMinHeight,
        ),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = mergedTextStyle,
        cursorBrush = SolidColor(colors.cursorColor),
        interactionSource = interactionSource,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        decorator = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                visualTransformation = VisualTransformation.None,
                innerTextField = innerTextField,
                singleLine = singleLine,
                enabled = enabled,
                isError = false,
                interactionSource = interactionSource,
                colors = colors,
                placeholder = placeholder,
                contentPadding = textPadding,
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = enabled,
                        isError = false,
                        interactionSource = interactionSource,
                        colors = colors,
                    )
                    leadingIcon?.let { icon ->
                        Box(
                            Modifier.fillMaxSize().padding(start = startInset),
                            contentAlignment = Alignment.CenterStart,
                            content = { icon() },
                        )
                    }
                    trailingIcon?.let { icon ->
                        Box(
                            Modifier.fillMaxSize().padding(end = endInset),
                            contentAlignment = Alignment.CenterEnd,
                            content = { icon() },
                        )
                    }
                },
            )
        },
    )
}

/**
 * The design's `.seg`: one bordered strip of options, the selected one filled with the accent
 * background. Options are laid out in the given order; [selected] is the index of the active one.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    testTagFor: ((Int) -> String)? = null,
) {
    val shape = MaterialTheme.shapes.small
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier
            .clip(shape)
            .border(1.dp, outline, shape)
            .height(IntrinsicSize.Min),
    ) {
        options.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
            val on = index == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (on) FontWeight.SemiBold else null,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .background(if (on) RuleblendTheme.extraColors.accentBackground else Color.Transparent)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(index) })
                    .then(if (testTagFor != null) Modifier.testTag(testTagFor(index)) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** The design's `.sw`: a 30 × 18 dp switch, accent when on, faint when off. */
@Composable
fun MiniSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track = if (checked) MaterialTheme.colorScheme.primary else RuleblendTheme.extraColors.faint
    val thumbOffset by animateDpAsState(if (checked) 14.dp else 2.dp)
    Box(
        modifier
            .size(width = 30.dp, height = 18.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Box(
            Modifier
                .padding(start = thumbOffset, top = 2.dp)
                .size(14.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White),
        )
    }
}

/**
 * Side of the square the disclosure triangle is drawn in. The arrow is a control rather than a word,
 * so it is sized as one — big enough to see and to hit, whatever type the label beside it uses.
 */
val DisclosureArrowSize = 12.dp

/** Base and depth of the triangle inside that square: a caret, not an equilateral wedge. */
private val DisclosureArrowBase = 8.dp
private val DisclosureArrowDepth = 6.dp

/**
 * The open/closed triangle of every collapsible list in the app. Drawn rather than typed: the glyphs
 * `\u25be` / `\u25b8` sit below the middle of their line box, which left every arrow in the app a pixel
 * low against its label, and their line box made the row taller than the label needed. A path is
 * centred in its own square by construction, so a row of any height centres the arrow with it.
 */
@Composable
fun DisclosureArrow(
    open: Boolean,
    modifier: Modifier = Modifier,
    color: Color = RuleblendTheme.extraColors.faint,
) {
    Canvas(modifier.size(DisclosureArrowSize)) {
        val base = DisclosureArrowBase.toPx()
        val depth = DisclosureArrowDepth.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path().apply {
            if (open) {
                moveTo(cx - base / 2f, cy - depth / 2f)
                lineTo(cx + base / 2f, cy - depth / 2f)
                lineTo(cx, cy + depth / 2f)
            } else {
                moveTo(cx - depth / 2f, cy - base / 2f)
                lineTo(cx + depth / 2f, cy)
                lineTo(cx - depth / 2f, cy + base / 2f)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** Paired controls for folding a whole group without separating the two actions on wrap. */
@Composable
fun FoldButtons(
    onExpand: (() -> Unit)?,
    onCollapse: (() -> Unit)?,
    expandHint: String,
    collapseHint: String,
    tagPrefix: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FoldButton("+", expandHint, "$tagPrefix-expand-all", onExpand)
        FoldButton("−", collapseHint, "$tagPrefix-collapse-all", onCollapse)
    }
}

@Composable
private fun FoldButton(glyph: String, hint: String, tag: String, onClick: (() -> Unit)?) {
    RuleblendTooltip(hint) {
        CompactOutlinedButton(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            modifier = Modifier.size(IconActionButtonSize).testTag(tag)
                .semantics { contentDescription = hint },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(glyph, style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp))
        }
    }
}
