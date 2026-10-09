package dev.ruleblend.app.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.util.tabSemantics
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource as composePainterResource

@Composable
fun AppShell(
    route: AppRoute,
    labelFor: (AppRoute) -> String,
    onNavigate: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the sidebar shows labels; the caller owns it so the choice can outlive the window. */
    sidebarExpanded: Boolean = true,
    onSidebarExpandedChange: (Boolean) -> Unit = {},
    onOpenCommands: () -> Unit = {},
    /** Trailing action of the top bar, owned by the current surface; most routes have none. */
    topBarAction: (@Composable () -> Unit)? = null,
    /** Drawn over the whole window, rail included, inside the shell's focus so chords survive it. */
    overlay: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Shortcuts belong to the window, not to a surface. The preview pass runs from the root down to
    // whatever holds focus, so they work while a screen's own text field is being typed in.
    val shortcuts = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var holdsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { shortcuts.requestFocus() } }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(shortcuts)
            // A focused node that leaves composition — a closed palette, a field on the surface just
            // switched away from — clears focus from the whole window instead of handing it to its
            // parent, and a key with nowhere focused never reaches this preview pass. The shell takes
            // focus back once the change has settled, unless something inside claimed it meanwhile.
            .onFocusChanged { state ->
                holdsFocus = state.hasFocus
                if (!state.hasFocus) {
                    scope.launch { if (!holdsFocus) runCatching { shortcuts.requestFocus() } }
                }
            }
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val chord = event.isMetaPressed || event.isCtrlPressed
                when (val shortcut = shortcutFor(event.key, chord)) {
                    null -> false
                    AppShortcut.OpenCommands -> {
                        onOpenCommands()
                        true
                    }
                    AppShortcut.ToggleSidebar -> {
                        onSidebarExpandedChange(!sidebarExpanded)
                        true
                    }
                    is AppShortcut.Go -> {
                        onNavigate(shortcut.route)
                        true
                    }
                }
            }
            .testTag("app-shell"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                NavigationSidebar(
                    route = route,
                    labelFor = labelFor,
                    onNavigate = onNavigate,
                    expanded = sidebarExpanded,
                    onToggleExpanded = { onSidebarExpandedChange(!sidebarExpanded) },
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(labelFor(route), onOpenCommands, topBarAction)
                    Box(Modifier.fillMaxSize().testTag("route-${route.name.lowercase()}"), contentAlignment = Alignment.TopStart) { content() }
                }
            }
            overlay?.invoke()
        }
    }
}

/**
 * One layout animated between two widths rather than two layouts swapped: the icons keep their
 * column while the width folds, so the eye follows the rail instead of watching it rebuild.
 */
@Composable
private fun NavigationSidebar(
    route: AppRoute,
    labelFor: (AppRoute) -> String,
    onNavigate: (AppRoute) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val dimensions = RuleblendTheme.dimensions
    val icons = RuleblendTheme.icons
    val progress by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(SidebarAnimationMillis, easing = FastOutSlowInEasing),
        label = "sidebar",
    )
    // Labels are gone before the column is narrow enough to cut them and return only once it is wide
    // enough again, so no frame shows a clipped word.
    val labelAlpha = ((progress - LabelFadeStart) / (1f - LabelFadeStart)).coerceIn(0f, 1f)
    Box(
        Modifier
            .width(lerp(dimensions.sidebarCollapsedWidth, dimensions.sidebarExpandedWidth, progress))
            .fillMaxHeight()
            .clipToBounds()
            .background(RuleblendTheme.extraColors.rail)
            .testTag("nav-sidebar"),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = dimensions.sidebarPadding,
                    end = dimensions.sidebarPadding,
                    top = dimensions.sidebarHeaderTopPadding,
                    bottom = dimensions.sidebarPadding,
                ),
        ) {
            SidebarHeader(expanded = expanded, progress = progress, labelAlpha = labelAlpha, onToggleExpanded = onToggleExpanded)
            Spacer(Modifier.height(dimensions.sidebarSectionGap))
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dimensions.sidebarItemGap),
            ) {
                AppRoute.primary.forEach { destination ->
                    SidebarItem(
                        destination = destination,
                        icon = when (destination) {
                            AppRoute.HOME -> icons.home
                            AppRoute.PLACE -> icons.place
                            AppRoute.LIBRARY -> icons.library
                            AppRoute.COVERAGE -> icons.coverage
                            else -> error("Only top-level destinations belong in the main sidebar")
                        },
                        label = labelFor(destination),
                        shortcut = shortcutLabel(destination),
                        progress = progress,
                        labelAlpha = labelAlpha,
                        selected = route == destination || route == AppRoute.RESOLVE && destination == AppRoute.HOME,
                        onClick = { onNavigate(destination) },
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = dimensions.sidebarDividerInset),
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(dimensions.sidebarFooterGap))
            SidebarItem(
                destination = AppRoute.SETTINGS,
                icon = icons.settings,
                label = labelFor(AppRoute.SETTINGS),
                shortcut = shortcutLabel(AppRoute.SETTINGS),
                progress = progress,
                labelAlpha = labelAlpha,
                selected = route == AppRoute.SETTINGS,
                onClick = { onNavigate(AppRoute.SETTINGS) },
            )
        }
        VerticalDivider(Modifier.align(Alignment.CenterEnd), color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The brand tile sits on the icon column in both states. Expanded, the toggle has a place of its own
 * at the end of the row; collapsed there is no room for it, so the tile itself becomes the way back
 * and shows the expand glyph while it is pointed at or focused.
 */
@Composable
private fun SidebarHeader(expanded: Boolean, progress: Float, labelAlpha: Float, onToggleExpanded: () -> Unit) {
    val dimensions = RuleblendTheme.dimensions
    val strings = LocalStrings.current
    val brandInset = lerp(
        (dimensions.sidebarItemHeight - dimensions.sidebarBrandSize) / 2,
        dimensions.sidebarItemPadding + (dimensions.sidebarIconSize - dimensions.sidebarBrandSize) / 2,
        progress,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(dimensions.sidebarHeaderHeight)
            .padding(start = brandInset, end = brandInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            BrandTile(Modifier)
        } else {
            val label = strings.navExpandSidebar
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val focused by interactions.collectIsFocusedAsState()
            RuleblendTooltip("$label  $ToggleSidebarShortcutLabel") {
                BrandTile(
                    modifier = Modifier
                        .hoverable(interactions)
                        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onToggleExpanded)
                        .semantics { contentDescription = label }
                        .testTag("nav-sidebar-toggle"),
                    showExpand = hovered || focused,
                )
            }
        }
        if (labelAlpha > 0f) {
            Text(
                text = "Ruleblend",
                modifier = Modifier.weight(1f).padding(start = dimensions.sidebarLabelGap).alpha(labelAlpha),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            val label = strings.navCollapseSidebar
            RuleblendTooltip("$label  $ToggleSidebarShortcutLabel") {
                IconButton(
                    onClick = onToggleExpanded,
                    enabled = expanded,
                    modifier = Modifier
                        .size(dimensions.sidebarToggleSize)
                        .alpha(labelAlpha)
                        .testTag("nav-sidebar-toggle"),
                ) {
                    Icon(
                        painter = composePainterResource(RuleblendTheme.icons.sidebarCollapse),
                        contentDescription = label,
                        modifier = Modifier.size(dimensions.sidebarIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandTile(modifier: Modifier, showExpand: Boolean = false) {
    val dimensions = RuleblendTheme.dimensions
    val shape = RoundedCornerShape(dimensions.sidebarBrandCornerRadius)
    Box(
        modifier
            .size(dimensions.sidebarBrandSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (showExpand) {
            Icon(
                painter = composePainterResource(RuleblendTheme.icons.sidebarExpand),
                contentDescription = null,
                modifier = Modifier.size(dimensions.sidebarIconSize),
                tint = RuleblendTheme.extraColors.railSelectedContent,
            )
        } else {
            Text(
                text = "R",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun SidebarItem(
    destination: AppRoute,
    icon: DrawableResource,
    label: String,
    shortcut: String?,
    progress: Float,
    labelAlpha: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val dimensions = RuleblendTheme.dimensions
    val contentColor =
        if (selected) RuleblendTheme.extraColors.railSelectedContent else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(dimensions.sidebarItemCornerRadius)
    // Collapsed, a destination is a square with the icon in its middle; expanded, the icon moves to
    // the label's inset. The two differ by a few dp, so the icon drifts rather than jumps.
    val iconInset = lerp(
        (dimensions.sidebarItemHeight - dimensions.sidebarIconSize) / 2,
        dimensions.sidebarItemPadding,
        progress,
    )
    RuleblendTooltip(
        text = if (shortcut == null) label else "$label  $shortcut",
        modifier = Modifier.fillMaxWidth().height(dimensions.sidebarItemHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
                .testTag("nav-${destination.name.lowercase()}")
                .tabSemantics(label, selected),
        ) {
            Row(
                Modifier.fillMaxSize().padding(start = iconInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = composePainterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(dimensions.sidebarIconSize),
                    tint = contentColor,
                )
                if (labelAlpha > 0f) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(start = dimensions.sidebarLabelGap).alpha(labelAlpha),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(dimensions.sidebarIndicatorWidth, dimensions.sidebarIndicatorHeight)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private const val SidebarAnimationMillis = 220

/** Share of the fold during which labels stay hidden. */
private const val LabelFadeStart = 0.6f

@Composable
private fun TopBar(title: String, onOpenCommands: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val height = RuleblendTheme.dimensions.topBarHeight
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                text = title,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = RuleblendTheme.dimensions.contentPadding),
                style = MaterialTheme.typography.titleLarge,
            )
            // The shortcut is only universal if it is also discoverable without knowing it exists.
            var focused by remember { mutableStateOf(false) }
            Text(
                text = LocalStrings.current.commandPill,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 360.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.background)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.small,
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(role = Role.Button, onClick = onOpenCommands)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("command-pill"),
            )
            action?.let {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(horizontal = RuleblendTheme.dimensions.contentPadding),
                ) {
                    it()
                }
            }
            HorizontalDivider(Modifier.align(Alignment.BottomStart), color = MaterialTheme.colorScheme.outline)
        }
    }
}
