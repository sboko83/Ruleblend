package dev.ruleblend.app.library

import androidx.compose.runtime.Composable
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.util.copyToClipboard

/** Actions on the complete source text, independent of truncation or translation in the preview. */
@Composable
internal fun BlockContextMenu(text: String, onEdit: (() -> Unit)?, content: @Composable () -> Unit) {
    val strings = LocalStrings.current
    CompactContextMenu(
        items = buildList<Pair<String, () -> Unit>> {
            add(strings.setCopy to { copyToClipboard(text) })
            onEdit?.let { add(strings.libEditFocus to it) }
        },
        content = content,
    )
}
