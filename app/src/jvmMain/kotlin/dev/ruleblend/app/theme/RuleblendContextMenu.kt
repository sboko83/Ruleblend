package dev.ruleblend.app.theme

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ContextMenuState.Status.Closed
import androidx.compose.foundation.ContextMenuState.Status.Open
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A [ContextMenuRepresentation] that renders right-click menus through Material3 [DropdownMenu],
 * so they inherit [RuleblendTheme] (the default representation paints a native Swing `JPopupMenu` that
 * ignores the Compose theme). Items close the menu on click by flipping the state back to [Closed].
 */
class RuleblendContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        when (val status = state.status) {
            Closed -> Unit
            is Open -> {
                Box(Modifier.offset { IntOffset(status.rect.left.toInt(), status.rect.top.toInt()) }) {
                    DropdownMenu(expanded = true, onDismissRequest = { state.status = Closed }) {
                        items().forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label, style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    state.status = Closed
                                    item.onClick()
                                },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
