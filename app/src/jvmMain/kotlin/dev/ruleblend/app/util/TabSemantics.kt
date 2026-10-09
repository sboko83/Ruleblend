package dev.ruleblend.app.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

/**
 * Names a tab and says whether it is the chosen one. `selected` alone reaches Compose tests but not
 * macOS: AppKit reads a tab's selection from its accessible value, and the desktop bridge only
 * provides one for a toggleable state.
 */
fun Modifier.tabSemantics(description: String, selected: Boolean): Modifier = semantics {
    contentDescription = description
    this.selected = selected
    toggleableState = ToggleableState(selected)
}
