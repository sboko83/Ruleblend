package dev.ruleblend.app.library

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition

/** Right-click menu wrapper: [items] are label-to-action pairs. */
@Composable
fun ContextMenu(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    ContextMenuArea(items = { items.map { (label, action) -> ContextMenuItem(label, action) } }) {
        content()
    }
}

/** Dense context menu without the default top and bottom padding around its items. */
@Composable
fun CompactContextMenu(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContextMenuRepresentation provides CompactContextMenuRepresentation) {
        ContextMenuArea(items = { items.map { (label, action) -> ContextMenuItem(label, action) } }) {
            content()
        }
    }
}

/**
 * [CompactContextMenu] for a surface whose items depend on where it was pressed: [items] is read when
 * the menu opens, and [state] is the caller's, so it can tell while the menu is up.
 */
@Composable
fun CompactContextMenu(
    state: ContextMenuState,
    items: () -> List<Pair<String, () -> Unit>>,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContextMenuRepresentation provides CompactContextMenuRepresentation) {
        ContextMenuArea(items = { items().map { (label, action) -> ContextMenuItem(label, action) } }, state = state) {
            content()
        }
    }
}

private object CompactContextMenuRepresentation : ContextMenuRepresentation {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return

        val menuItems = items()
        if (menuItems.isEmpty()) {
            SideEffect { state.status = ContextMenuState.Status.Closed }
            return
        }

        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(status.rect.center),
            onDismissRequest = { state.status = ContextMenuState.Status.Closed },
        ) {
            Column(
                modifier = Modifier
                    .shadow(3.dp, RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 112.dp, max = 280.dp),
            ) {
                menuItems.forEach { item ->
                    CompactContextMenuItem(item) {
                        state.status = ContextMenuState.Status.Closed
                        item.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactContextMenuItem(item: ContextMenuItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface)
            .hoverable(interaction)
            .clickable(enabled = item.enabled, onClickLabel = item.label, onClick = onClick)
            .padding(horizontal = 6.dp),
    ) {
        Text(
            item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}
