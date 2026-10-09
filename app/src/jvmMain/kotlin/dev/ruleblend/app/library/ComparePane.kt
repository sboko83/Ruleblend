package dev.ruleblend.app.library

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.compare.DiffRow
import dev.ruleblend.app.compare.DiffTransferButton
import dev.ruleblend.app.compare.SideBySideDiff
import dev.ruleblend.app.compare.hunkRowIndex
import dev.ruleblend.app.compare.sideBySideRows
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.SegmentedControl
import dev.ruleblend.app.theme.workingDialogSize
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.compare.CompareCandidates
import dev.ruleblend.core.compare.HunkApply
import dev.ruleblend.core.compare.HunkApplyException
import dev.ruleblend.core.compare.LineDiff
import dev.ruleblend.core.compare.LineHunk
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import kotlinx.coroutines.launch

/** Stable handles for the ComparePane interaction contract. */
internal const val CompareCandidatePickerTag = "compare-candidate-picker"
internal const val CompareLeftDraftTag = "compare-left-draft"
internal const val CompareRightDraftTag = "compare-right-draft"
internal const val CompareUndoTag = "compare-undo"
internal const val CompareResetLeftTag = "compare-reset-left"
internal const val CompareResetRightTag = "compare-reset-right"
internal const val CompareCopyRightToLeftTag = "compare-copy-right-to-left"
internal const val CompareCopyLeftToRightTag = "compare-copy-left-to-right"
internal const val CompareSaveLeftTag = "compare-save-left"
internal const val CompareSaveRightTag = "compare-save-right"
internal const val CompareSaveBothTag = "compare-save-both"
internal const val CompareSaveRuleNameTag = "compare-save-rule-name"
internal const val CompareSaveRuleBodyTag = "compare-save-rule-body"
internal const val CompareModeDiffTag = "compare-mode-diff"
internal const val CompareModeEditTag = "compare-mode-edit"
internal const val ComparePrevHunkTag = "compare-prev-hunk"
internal const val CompareNextHunkTag = "compare-next-hunk"

internal fun compareCandidateTag(key: LibraryObjectKey) = "compare-candidate-${key.id}"
internal fun compareCopyRightToLeftTag(index: Int) = "$CompareCopyRightToLeftTag-$index"
internal fun compareCopyLeftToRightTag(index: Int) = "$CompareCopyLeftToRightTag-$index"

/** One editable library object as a side of [ComparePane]. */
internal data class CompareSubject(
    val key: LibraryObjectKey,
    val name: String,
    val content: String,
    val readOnly: Boolean,
) {
    companion object {
        fun from(block: Block) = CompareSubject(
            key = LibraryObjectKey(block.type.objectKind(), block.id),
            name = block.name.ifBlank { block.id },
            content = block.content,
            readOnly = block.source != null,
        )

        fun from(skill: Skill) = CompareSubject(
            key = LibraryObjectKey(LibraryObjectKind.SKILL, skill.id),
            name = skill.name.ifBlank { skill.id },
            content = skill.content,
            readOnly = skill.source != null,
        )

        fun from(item: LibraryObject) = CompareSubject(
            key = item.key,
            name = item.name.ifBlank { item.key.id },
            content = item.content,
            readOnly = item.importedFromGit,
        )
    }
}

private data class CompareSnapshot(val left: String, val right: String)

/** Which surface the two bodies are shown on: read the difference, or type into either side. */
internal enum class CompareMode { DIFF, EDIT }

/**
 * Runs one hunk transfer, or answers null when the engine refuses it.
 *
 * Hunks are rebuilt from both drafts, so a hunk can only go stale between a click and the
 * recomposition that follows it. Refusing to overwrite unrelated text is the engine's contract and
 * must not reach the user as a crash.
 */
private inline fun transferred(hunk: LineHunk, apply: (LineHunk) -> String): String? = try {
    apply(hunk)
} catch (_: HunkApplyException) {
    null
}

/**
 * A two-draft working surface over the shared compare engine.
 *
 * The dialog intentionally owns only transient text. Saving goes back through [LibraryModel], so
 * a side is persisted as a normal next revision rather than as a separate compare format.
 */
@Composable
internal fun ComparePane(
    model: LibraryModel,
    source: CompareSubject,
    initialCandidateKey: LibraryObjectKey? = null,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val candidates = remember(source, model.catalog) {
        model.catalog.objects
            .filter { it.key.kind == source.key.kind && it.key != source.key }
            .let { items -> rankCandidates(source, items) }
    }
    var selectedKey by remember(source, initialCandidateKey) {
        mutableStateOf(candidates.find { it.key == initialCandidateKey }?.key ?: candidates.firstOrNull()?.key)
    }
    val selected = candidates.find { it.key == selectedKey }
    var pickerOpen by remember { mutableStateOf(false) }
    // The left draft outlives a candidate change: its transfers and typing are the user's work and
    // only the right side belongs to the candidate being swapped out. The saved bodies are what
    // reset returns to and what decides whether a side still has something to save.
    var left by remember(source) { mutableStateOf(source.content) }
    var savedLeft by remember(source) { mutableStateOf(source.content) }
    var right by remember(source, selectedKey) { mutableStateOf(selected?.content.orEmpty()) }
    var savedRight by remember(source, selectedKey) { mutableStateOf(selected?.content.orEmpty()) }
    // One undo entry pairs both drafts, so the stack cannot reach across a candidate change.
    val undo = remember(source, selectedKey) { mutableStateListOf<CompareSnapshot>() }
    val coroutineScope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(CompareMode.DIFF) }
    val size = workingDialogSize()

    RuleblendDialog(
        title = strings.compareTitle,
        onDismiss = onDismiss,
        confirmLabel = strings.actionClose,
        onConfirm = onDismiss,
        minWidth = size.width,
        maxWidth = size.width,
        usePlatformDefaultWidth = false,
        modifier = Modifier.width(size.width).heightIn(min = size.height, max = size.height),
    ) {
        if (candidates.isEmpty() || selected == null) {
            Text(strings.compareNoCandidates, style = MaterialTheme.typography.bodySmall)
            return@RuleblendDialog
        }

        val hunks = remember(left, right) { LineDiff.between(left, right) }
        val rows = remember(left, right, hunks) { sideBySideRows(left, right, hunks) }
        val listState = rememberLazyListState()
        var hunkCursor by remember(source, selectedKey) { mutableStateOf(0) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.compareChoose, style = MaterialTheme.typography.labelMedium)
            Box {
                CompactOutlinedButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier.testTag(CompareCandidatePickerTag),
                ) { Text(selected.name) }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    candidates.forEach { candidate ->
                        // A picker of twenty rules must stay a list, not a page: the row is as tall as
                        // its text and no taller.
                        DropdownMenuItem(
                            text = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        candidate.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    candidate.percent?.let {
                                        Text(
                                            "$it%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                selectedKey = candidate.key
                                pickerOpen = false
                            },
                            contentPadding = CompareMenuItemPadding,
                            modifier = Modifier.height(CompareMenuItemHeight).testTag(compareCandidateTag(candidate.key)),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f))
            SegmentedControl(
                options = listOf(strings.compareModeDiff, strings.compareModeEdit),
                selected = mode.ordinal,
                onSelect = { mode = CompareMode.entries[it] },
                testTagFor = { if (it == 0) CompareModeDiffTag else CompareModeEditTag },
            )
        }

        CompareToolbar(
            hunkCount = hunks.size,
            hunkCursor = hunkCursor,
            canResetLeft = !source.readOnly && left != savedLeft,
            canResetRight = !selected.readOnly && right != savedRight,
            canUndo = undo.isNotEmpty(),
            onJump = { index ->
                hunkCursor = index
                coroutineScope.launch { listState.animateScrollToItem(hunkRowIndex(rows, index)) }
            },
            onUndo = {
                undo.removeLastOrNull()?.let { (previousLeft, previousRight) ->
                    left = previousLeft
                    right = previousRight
                }
            },
            onResetLeft = { undo += CompareSnapshot(left, right); left = savedLeft },
            onResetRight = { undo += CompareSnapshot(left, right); right = savedRight },
        )

        val copyRightToLeft = { hunk: LineHunk ->
            transferred(hunk) { HunkApply.forward(left, hunk) }?.let {
                undo += CompareSnapshot(left, right)
                left = it
            }
            Unit
        }
        val copyLeftToRight = { hunk: LineHunk ->
            transferred(hunk) { HunkApply.reverse(right, hunk) }?.let {
                undo += CompareSnapshot(left, right)
                right = it
            }
            Unit
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (mode) {
                CompareMode.DIFF -> CompareDiffSurface(
                    rows = rows,
                    hunks = hunks,
                    listState = listState,
                    leftTitle = source.name,
                    rightTitle = selected.name,
                    canWriteLeft = !source.readOnly,
                    canWriteRight = !selected.readOnly,
                    onCopyRightToLeft = copyRightToLeft,
                    onCopyLeftToRight = copyLeftToRight,
                    onSaveAsRule = model::createRuleFromText,
                )
                CompareMode.EDIT -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompareDraft(
                        title = source.name,
                        value = left,
                        readOnly = source.readOnly,
                        tag = CompareLeftDraftTag,
                        onValueChange = { left = it },
                        modifier = Modifier.weight(1f),
                    )
                    CompareDraft(
                        title = selected.name,
                        value = right,
                        readOnly = selected.readOnly,
                        tag = CompareRightDraftTag,
                        onValueChange = { right = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                if (hunks.isEmpty()) strings.compareIdentical else strings.compareDifferences(hunks.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        saving = true
                        try {
                            if (model.saveComparedContent(source.key, left)) savedLeft = left
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && !source.readOnly && left != savedLeft,
                modifier = Modifier.testTag(CompareSaveLeftTag),
            ) { Text(strings.compareSaveLeft) }
            CompactOutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        saving = true
                        try {
                            if (model.saveComparedContent(selected.key, right)) savedRight = right
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving && !selected.readOnly && right != savedRight,
                modifier = Modifier.testTag(CompareSaveRightTag),
            ) { Text(strings.compareSaveRight) }
            CompactButton(
                onClick = {
                    coroutineScope.launch {
                        saving = true
                        try {
                            if (!source.readOnly && left != savedLeft &&
                                model.saveComparedContent(source.key, left)
                            ) {
                                savedLeft = left
                            }
                            if (!selected.readOnly && right != savedRight &&
                                model.saveComparedContent(selected.key, right)
                            ) {
                                savedRight = right
                            }
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving &&
                    (!source.readOnly && left != savedLeft || !selected.readOnly && right != savedRight),
                modifier = Modifier.testTag(CompareSaveBothTag),
            ) { Text(strings.compareSaveBoth) }
        }
    }
}

internal val CompareMenuItemHeight = 24.dp
internal val CompareMenuItemPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)

private data class RankedCompareCandidate(
    val key: LibraryObjectKey,
    val name: String,
    val content: String,
    val readOnly: Boolean,
    val percent: Int?,
)

/** Adapts the compare core's block-only picker to the Library's three text-bearing object kinds. */
private fun rankCandidates(source: CompareSubject, candidates: List<LibraryObject>): List<RankedCompareCandidate> {
    val ranked = CompareCandidates(
        Block(id = source.key.id, name = source.name, content = source.content),
        candidates.map { item -> Block(id = item.key.id, name = item.name, content = item.content) },
    )
    val byId = candidates.associateBy { it.key.id }
    fun view(id: String, percent: Int?) = byId.getValue(id).let { item ->
        RankedCompareCandidate(item.key, item.name, item.content, item.importedFromGit, percent)
    }
    return ranked.top.map { view(it.block.id, it.percent) } + ranked.remaining.map { view(it.block.id, null) }
}

/**
 * The read surface: both bodies aligned line by line, with the transfer arrows sitting on the very
 * rows they move. Reusable by anything comparing two texts — the Library dialog and the project file
 * editor share it so a difference always looks the same.
 */
@Composable
internal fun CompareDiffSurface(
    rows: List<DiffRow>,
    hunks: List<LineHunk>,
    listState: LazyListState,
    leftTitle: String,
    rightTitle: String,
    canWriteLeft: Boolean,
    canWriteRight: Boolean,
    onCopyRightToLeft: (LineHunk) -> Unit,
    onCopyLeftToRight: (LineHunk) -> Unit,
    /** Saves a difference as a new library rule; `null` leaves the diff without its context menu. */
    onSaveAsRule: ((name: String, content: String) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val menuState = remember { ContextMenuState() }
    // Where the last right press landed. The menu is built from it when it opens, and the hunk stays
    // picked on screen while the menu or the save dialog is about it.
    var pressed by remember { mutableStateOf<HunkPress?>(null) }
    var saving by remember { mutableStateOf<HunkPress?>(null) }
    val menuOpen = menuState.status is ContextMenuState.Status.Open
    val selectedHunk = saving?.hunkIndex ?: pressed?.hunkIndex?.takeIf { menuOpen }

    CompactContextMenu(
        state = menuState,
        items = {
            val press = pressed
            val hunk = press?.let { hunks.getOrNull(it.hunkIndex) }
            if (onSaveAsRule == null || press == null || hunk == null || hunkSideText(hunk, press.left).isBlank()) {
                emptyList()
            } else {
                listOf(strings.compareSaveAsRule to { saving = press })
            }
        },
    ) {
        SideBySideDiffWithTransfers(
            rows = rows,
            hunks = hunks,
            listState = listState,
            leftTitle = leftTitle,
            rightTitle = rightTitle,
            canWriteLeft = canWriteLeft,
            canWriteRight = canWriteRight,
            onCopyRightToLeft = onCopyRightToLeft,
            onCopyLeftToRight = onCopyLeftToRight,
            selectedHunk = selectedHunk,
            onSecondaryPress = if (onSaveAsRule == null) null else { hunkIndex, left ->
                pressed = hunkIndex?.let { HunkPress(it, left) }
            },
        )
    }

    val request = saving
    val hunk = request?.let { hunks.getOrNull(it.hunkIndex) }
    if (onSaveAsRule != null && request != null && hunk != null) {
        SaveHunkAsRuleDialog(
            initialContent = hunkSideText(hunk, request.left),
            onDismiss = { saving = null },
            onSave = { name, content ->
                onSaveAsRule(name, content)
                saving = null
            },
        )
    }
}

/** A right press on a hunk, and the side it landed on. */
private data class HunkPress(val hunkIndex: Int, val left: Boolean)

/**
 * The text a hunk holds on one side. A pure addition or removal has nothing on the other side, and
 * the grey filler opposite it is still the same difference — so that side gives the text there is.
 */
internal fun hunkSideText(hunk: LineHunk, left: Boolean): String {
    val lines = if (left) hunk.sourceLines else hunk.targetLines
    val other = if (left) hunk.targetLines else hunk.sourceLines
    return lines.ifEmpty { other }.joinToString("\n")
}

/**
 * A name to start the save dialog on: the first line with words in it, stripped of markdown heading,
 * list and emphasis marks. The user edits it anyway; a good first guess only saves typing.
 */
internal fun suggestedRuleName(text: String): String =
    text.lineSequence()
        .map { it.trim().trimStart('#', '-', '*', '+', '>', ' ').trim('*', '_', '`', ' ', ':') }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
        .take(SuggestedNameLength)
        .trim()

private const val SuggestedNameLength = 60

@Composable
private fun SideBySideDiffWithTransfers(
    rows: List<DiffRow>,
    hunks: List<LineHunk>,
    listState: LazyListState,
    leftTitle: String,
    rightTitle: String,
    canWriteLeft: Boolean,
    canWriteRight: Boolean,
    onCopyRightToLeft: (LineHunk) -> Unit,
    onCopyLeftToRight: (LineHunk) -> Unit,
    selectedHunk: Int?,
    onSecondaryPress: ((hunkIndex: Int?, left: Boolean) -> Unit)?,
) {
    val strings = LocalStrings.current
    SideBySideDiff(
        rows = rows,
        listState = listState,
        leftTitle = leftTitle,
        rightTitle = rightTitle,
        modifier = Modifier.fillMaxSize(),
        selectedHunk = selectedHunk,
        onSecondaryPress = onSecondaryPress,
    ) { hunkIndex ->
        val hunk = hunks.getOrNull(hunkIndex) ?: return@SideBySideDiff
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            RuleblendTooltip(strings.compareCopyToLeft) {
                DiffTransferButton(
                    glyph = "←",
                    enabled = canWriteLeft,
                    onClick = { onCopyRightToLeft(hunk) },
                    modifier = Modifier.testTag(compareCopyRightToLeftTag(hunkIndex)),
                )
            }
            RuleblendTooltip(strings.compareCopyToRight) {
                DiffTransferButton(
                    glyph = "→",
                    enabled = canWriteRight,
                    onClick = { onCopyLeftToRight(hunk) },
                    modifier = Modifier.testTag(compareCopyLeftToRightTag(hunkIndex)),
                )
            }
        }
    }
}

/**
 * Names the difference and lets the body be trimmed before it becomes a rule: a hunk is cut by the
 * diff, not by the author, and often carries a stray line on either end.
 */
@Composable
private fun SaveHunkAsRuleDialog(
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (name: String, content: String) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember(initialContent) { mutableStateOf(suggestedRuleName(initialContent)) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    val focusRequester = remember { FocusRequester() }
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.compareSaveAsRuleTitle,
        confirmLabel = strings.actionSave,
        confirmEnabled = name.isNotBlank() && content.isNotBlank(),
        onConfirm = { onSave(name.trim(), content) },
        dismissLabel = strings.actionCancel,
    ) {
        LabelledField(strings.fieldName) {
            RuleblendOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag(CompareSaveRuleNameTag),
            )
        }
        LabelledField(strings.compareSaveAsRuleBody) {
            RuleblendOutlinedTextField(
                value = content,
                onValueChange = { content = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 320.dp).testTag(CompareSaveRuleBodyTag),
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** Difference navigation on the left, whole-draft actions on the right. */
@Composable
private fun CompareToolbar(
    hunkCount: Int,
    hunkCursor: Int,
    canResetLeft: Boolean,
    canResetRight: Boolean,
    canUndo: Boolean,
    onJump: (Int) -> Unit,
    onUndo: () -> Unit,
    onResetLeft: () -> Unit,
    onResetRight: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        RuleblendTooltip(strings.comparePrevDifference) {
            CompactGhostButton(
                onClick = { onJump((hunkCursor - 1 + hunkCount) % hunkCount.coerceAtLeast(1)) },
                enabled = hunkCount > 0,
                modifier = Modifier.testTag(ComparePrevHunkTag),
            ) { Text("↑") }
        }
        RuleblendTooltip(strings.compareNextDifference) {
            CompactGhostButton(
                onClick = { onJump((hunkCursor + 1) % hunkCount.coerceAtLeast(1)) },
                enabled = hunkCount > 0,
                modifier = Modifier.testTag(CompareNextHunkTag),
            ) { Text("↓") }
        }
        Text(
            if (hunkCount == 0) "—" else strings.compareHunkPosition(hunkCursor + 1, hunkCount),
            style = MaterialTheme.typography.labelSmall,
            color = RuleblendTheme.extraColors.faint,
        )
        Box(Modifier.weight(1f))
        CompactGhostButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.testTag(CompareUndoTag),
        ) { Text(strings.compareUndo) }
        RuleblendTooltip(strings.compareResetLeft) {
            CompactGhostButton(
                onClick = onResetLeft,
                enabled = canResetLeft,
                modifier = Modifier.testTag(CompareResetLeftTag),
            ) { Text("↺") }
        }
        RuleblendTooltip(strings.compareResetRight) {
            CompactGhostButton(
                onClick = onResetRight,
                enabled = canResetRight,
                modifier = Modifier.testTag(CompareResetRightTag),
            ) { Text("↻") }
        }
    }
}

@Composable
private fun CompareDraft(
    title: String,
    value: String,
    readOnly: Boolean,
    tag: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        RuleblendOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(tag),
        )
    }
}
