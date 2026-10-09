package dev.ruleblend.app.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.CompactOutlinedButton

/**
 * Where a rule belongs: the whole machine, or one project. The same control serves the library
 * editor and the adopt form — a rule saved from a project file must be pinnable to that project on
 * the spot, and a scope picked in one surface has to read the same in the other.
 *
 * [resetKey] is what a fresh pick belongs to (a draft id, a file): the open/closed state of the menu
 * is per subject, not per screen.
 */
@Composable
internal fun ScopePicker(
    scope: String?,
    scopes: List<String>,
    onSelect: (String?) -> Unit,
    resetKey: Any? = null,
) {
    val strings = LocalStrings.current
    var expanded by remember(resetKey) { mutableStateOf(false) }
    Column {
        FieldLabel(strings.fieldScope)
        Box {
            CompactOutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().testTag("library-scope-picker"),
            ) {
                Text(
                    scope?.let(::projectScopeLabel) ?: strings.libGlobalScope,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(strings.libGlobalScope) },
                    onClick = { onSelect(null); expanded = false },
                    contentPadding = CompareMenuItemPadding,
                    modifier = Modifier.height(CompareMenuItemHeight).testTag("library-scope:global"),
                )
                scopes.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(projectScopeLabel(project), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { onSelect(project); expanded = false },
                        contentPadding = CompareMenuItemPadding,
                        modifier = Modifier.height(CompareMenuItemHeight).testTag("library-scope:$project"),
                    )
                }
            }
        }
    }
}
