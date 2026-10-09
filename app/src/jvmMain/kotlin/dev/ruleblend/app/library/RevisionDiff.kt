package dev.ruleblend.app.library

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.compare.LineDiffText
import dev.ruleblend.app.compare.unifiedDiffSections
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.workingDialogSize
import dev.ruleblend.core.storage.LibraryRevision
import kotlinx.coroutines.launch

/** Test tag on the diff body, so a test can assert what a revision shows without reading pixels. */
const val RevisionDiffBodyTag: String = "revision-diff-body"

/**
 * What one revision did, in the app's own window rather than in the inspector column: a diff is made
 * of whole source lines, and the inspector is a third of the window wide — every line there would
 * wrap twice and the shape of the change would be lost.
 *
 * The revision list stays on the left so the reader can walk the history without closing and
 * reopening the window: the point of a history is comparing its steps, not reading one of them.
 */
@Composable
internal fun RevisionDiffDialog(model: LibraryModel, title: String, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val revision = model.openDiff ?: return
    val scope = rememberCoroutineScope()
    val size = workingDialogSize()
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.libDiffTitle(title),
        confirmLabel = strings.actionClose,
        onConfirm = onDismiss,
        modifier = Modifier.size(size),
        minWidth = size.width,
        maxWidth = size.width,
        usePlatformDefaultWidth = false,
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RevisionList(
                revisions = model.history,
                selected = revision,
                onSelect = { picked -> scope.launch { model.showDiff(picked) } },
                modifier = Modifier.width(RevisionListWidth).fillMaxHeight(),
            )
            DiffBody(model.openDiffText, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun RevisionList(
    revisions: List<LibraryRevision>,
    selected: LibraryRevision,
    onSelect: (LibraryRevision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected.id) {
        val index = revisions.indexOfFirst { it.id == selected.id }
        if (index >= 0) listState.scrollToItem(index)
    }
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
    ) {
        LazyColumn(Modifier.fillMaxHeight().padding(4.dp), state = listState) {
            items(revisions, key = { "diff-revision:${it.id}" }) { item ->
                val current = item.id == selected.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .testTag("revision-dialog-row:${item.id}")
                        .clickable { onSelect(item) }
                        .background(
                            if (current) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        item.message,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            item.time.formatDay(),
                            style = MaterialTheme.typography.labelSmall,
                            color = RuleblendTheme.extraColors.faint,
                        )
                        RevisionCounts(item)
                    }
                }
            }
        }
    }
}

/** "+12 −3" in the colours the diff itself uses, so the row reads as a small preview of it. */
@Composable
internal fun RevisionCounts(revision: LibraryRevision) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "+${revision.added}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
            color = RuleblendTheme.extraColors.success,
        )
        Text(
            "−${revision.removed}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
            color = RuleblendTheme.extraColors.danger,
        )
    }
}

@Composable
private fun DiffBody(diff: String, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val sections = remember(diff) { unifiedDiffSections(diff) }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .testTag(RevisionDiffBodyTag),
    ) {
        if (sections.isEmpty()) {
            Text(
                strings.libDiffEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = RuleblendTheme.extraColors.faint,
                modifier = Modifier.padding(10.dp),
            )
            return@Box
        }
        LazyColumn(Modifier.fillMaxHeight().horizontalScroll(horizontal).padding(6.dp), state = listState) {
            items(sections) { section ->
                LineDiffText(section.source, section.target, singleLine = true)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).padding(vertical = 2.dp),
            style = defaultScrollbarStyle(),
        )
    }
}

private val RevisionListWidth = 200.dp
