package dev.ruleblend.app.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.compare.hunkRowIndex
import dev.ruleblend.app.compare.sideBySideRows
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.library.CompareDiffSurface
import dev.ruleblend.app.library.CompareMenuItemHeight
import dev.ruleblend.app.library.CompareMenuItemPadding
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.workingDialogSize
import dev.ruleblend.core.compare.CompareCandidates
import dev.ruleblend.core.compare.HunkApply
import dev.ruleblend.core.compare.HunkApplyException
import dev.ruleblend.core.compare.LineDiff
import dev.ruleblend.core.compare.LineHunk
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.Block
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.launch

internal const val FileEditCompareTag = "file-edit-compare"
internal const val FileComparePickerTag = "file-compare-picker"
internal const val FileCompareApplyTag = "file-compare-apply"
internal const val FileCompareUndoTag = "file-compare-undo"

/** One instruction file of the fleet, offered as the right-hand side of a comparison. */
internal data class FileCompareCandidate(
    val path: Path,
    val place: String,
    val content: String,
) {
    val label: String get() = "$place · ${path.fileName ?: path}"
}

/**
 * Every instruction file Ruleblend knows about, except the one being edited. A file in another
 * project is the comparison worth having here: the same rule set drifts between projects, and that
 * drift is exactly what the reader opened this for.
 */
internal fun fileCompareCandidates(targets: List<Target>, exclude: Path): List<FileCompareCandidate> {
    val excluded = exclude.toAbsolutePath().normalize()
    return targets
        .flatMap { target -> target.files().map { target.name to it } }
        .distinctBy { (_, path) -> path.toAbsolutePath().normalize() }
        .filter { (_, path) -> path.toAbsolutePath().normalize() != excluded && path.exists() }
        .mapNotNull { (place, path) ->
            runCatching { FileCompareCandidate(path, place, path.readText()) }.getOrNull()
        }
}

/** Most-alike first: the compare engine already ranks bodies, and a file is just another body. */
internal fun rankFileCandidates(
    source: String,
    candidates: List<FileCompareCandidate>,
): List<FileCompareCandidate> {
    if (candidates.isEmpty()) return candidates
    val byId = candidates.associateBy { it.path.toString() }
    val ranked = CompareCandidates(
        Block(id = "source", name = "source", content = source),
        candidates.map { Block(id = it.path.toString(), name = it.label, content = it.content) },
    )
    return (ranked.top + ranked.remaining).mapNotNull { byId[it.block.id] }
}

/**
 * The project-side twin of the Library compare dialog: the file being edited on the left, any other
 * file of the fleet on the right, the same aligned diff and the same hunk transfers between them.
 *
 * Nothing is written to disk here. Transfers land in the editor's draft, and the editor's own Save is
 * still what decides whether they reach the file — a compare view must not write behind that.
 */
@Composable
internal fun FileComparePane(
    fileName: String,
    text: String,
    targets: List<Target>,
    path: Path,
    onApply: (String) -> Unit,
    onSaveAsRule: (name: String, content: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val candidates = remember(path, targets) {
        rankFileCandidates(text, fileCompareCandidates(targets, path))
    }
    var selectedPath by remember(path) { mutableStateOf(candidates.firstOrNull()?.path) }
    val selected = candidates.find { it.path == selectedPath }
    var pickerOpen by remember { mutableStateOf(false) }
    var left by remember(path) { mutableStateOf(text) }
    val undo = remember(path) { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val size = workingDialogSize()

    RuleblendDialog(
        title = strings.compareFileTitle(fileName),
        onDismiss = onDismiss,
        confirmLabel = strings.actionClose,
        onConfirm = onDismiss,
        minWidth = size.width,
        maxWidth = size.width,
        usePlatformDefaultWidth = false,
        modifier = Modifier.width(size.width).heightIn(min = size.height, max = size.height),
    ) {
        if (selected == null) {
            Text(strings.compareFileNoCandidates, style = MaterialTheme.typography.bodySmall)
            return@RuleblendDialog
        }

        val hunks = remember(left, selected) { LineDiff.between(left, selected.content) }
        val rows = remember(left, selected, hunks) { sideBySideRows(left, selected.content, hunks) }
        val listState = rememberLazyListState()
        var hunkCursor by remember(path, selectedPath) { mutableStateOf(0) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.compareChoose, style = MaterialTheme.typography.labelMedium)
            Box {
                CompactOutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.testTag(FileComparePickerTag),
                ) { Text(selected.label) }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    candidates.forEach { candidate ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    candidate.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                selectedPath = candidate.path
                                pickerOpen = false
                            },
                            contentPadding = CompareMenuItemPadding,
                            modifier = Modifier.height(CompareMenuItemHeight),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f))
            RuleblendTooltip(strings.comparePrevDifference) {
                CompactGhostButton(
                    onClick = {
                        hunkCursor = (hunkCursor - 1 + hunks.size) % hunks.size.coerceAtLeast(1)
                        scope.launch { listState.animateScrollToItem(hunkRowIndex(rows, hunkCursor)) }
                    },
                    enabled = hunks.isNotEmpty(),
                ) { Text("↑") }
            }
            RuleblendTooltip(strings.compareNextDifference) {
                CompactGhostButton(
                    onClick = {
                        hunkCursor = (hunkCursor + 1) % hunks.size.coerceAtLeast(1)
                        scope.launch { listState.animateScrollToItem(hunkRowIndex(rows, hunkCursor)) }
                    },
                    enabled = hunks.isNotEmpty(),
                ) { Text("↓") }
            }
            Text(
                if (hunks.isEmpty()) "—" else strings.compareHunkPosition(hunkCursor + 1, hunks.size),
                style = MaterialTheme.typography.labelSmall,
                color = RuleblendTheme.extraColors.faint,
            )
            CompactGhostButton(
                onClick = { undo.removeLastOrNull()?.let { left = it } },
                enabled = undo.isNotEmpty(),
                modifier = Modifier.testTag(FileCompareUndoTag),
            ) { Text(strings.compareUndo) }
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            CompareDiffSurface(
                rows = rows,
                hunks = hunks,
                listState = listState,
                leftTitle = fileName,
                rightTitle = selected.label,
                canWriteLeft = true,
                // The other side is somebody else's file; this dialog only ever changes the draft it
                // was opened from.
                canWriteRight = false,
                onCopyRightToLeft = { hunk ->
                    try {
                        val next = HunkApply.forward(left, hunk)
                        undo += left
                        left = next
                    } catch (_: HunkApplyException) {
                        // A hunk that no longer fits the draft is refused, not forced.
                    }
                },
                onCopyLeftToRight = {},
                onSaveAsRule = onSaveAsRule,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                if (hunks.isEmpty()) strings.compareIdentical else strings.compareDifferences(hunks.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CompactButton(
                onClick = { onApply(left) },
                enabled = left != text,
                modifier = Modifier.testTag(FileCompareApplyTag),
            ) { Text(strings.compareFileApply) }
        }
    }
}
