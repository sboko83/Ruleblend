package dev.ruleblend.app.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.compare.LineHunk
import dev.ruleblend.core.compare.WordDiffKind

/** What one aligned row says about the pair of lines it holds. */
internal enum class DiffRowKind { CONTEXT, CHANGED, REMOVED, ADDED }

/**
 * One row of the side-by-side view: the same vertical slot on both sides.
 *
 * A row always exists on both sides even when one of them has no line there, which is what keeps the
 * two columns scrolling as one — the reader never has to match a left line to a right line by eye.
 */
internal data class DiffRow(
    val kind: DiffRowKind,
    val leftNumber: Int?,
    val rightNumber: Int?,
    val left: List<RenderedDiffSegment>?,
    val right: List<RenderedDiffSegment>?,
    /** The hunk this row belongs to, as an index into the list the transfer buttons were built from. */
    val hunkIndex: Int?,
    /** Only the first row of a hunk carries its transfer buttons. */
    val hunkStart: Boolean,
)

/**
 * Aligns two bodies into one row list against [hunks], which the caller already computed so the
 * transfer buttons and the rows agree on what a hunk is.
 */
internal fun sideBySideRows(source: String, target: String, hunks: List<LineHunk>): List<DiffRow> {
    val sourceLines = diffDisplayLines(source)
    val targetLines = diffDisplayLines(target)
    val rows = mutableListOf<DiffRow>()
    var sourceIndex = 0
    var targetIndex = 0

    fun context(text: String) {
        rows += DiffRow(
            kind = DiffRowKind.CONTEXT,
            leftNumber = sourceIndex + 1,
            rightNumber = targetIndex + 1,
            left = listOf(RenderedDiffSegment(text, false)),
            right = listOf(RenderedDiffSegment(text, false)),
            hunkIndex = null,
            hunkStart = false,
        )
    }

    hunks.forEachIndexed { hunkIndex, hunk ->
        while (sourceIndex < hunk.source.start && sourceIndex < sourceLines.size) {
            context(sourceLines[sourceIndex])
            sourceIndex++
            targetIndex++
        }
        var first = true
        // A hunk that only moves the final line break has no lines to show, yet it is still a hunk the
        // user can transfer: it gets an empty row so its buttons have somewhere to live.
        if (hunk.changes.isEmpty()) {
            rows += DiffRow(DiffRowKind.CHANGED, null, null, emptyList(), emptyList(), hunkIndex, true)
        }
        hunk.changes.forEach { change ->
            val sourceLine = change.source
            val targetLine = change.target
            when {
                sourceLine != null && targetLine != null -> {
                    rows += DiffRow(
                        kind = DiffRowKind.CHANGED,
                        leftNumber = sourceIndex + 1,
                        rightNumber = targetIndex + 1,
                        left = change.words.filter { it.kind != WordDiffKind.ADDED }
                            .map { RenderedDiffSegment(it.text, it.kind != WordDiffKind.CONTEXT) },
                        right = change.words.filter { it.kind != WordDiffKind.REMOVED }
                            .map { RenderedDiffSegment(it.text, it.kind != WordDiffKind.CONTEXT) },
                        hunkIndex = hunkIndex,
                        hunkStart = first,
                    )
                    sourceIndex++
                    targetIndex++
                }
                sourceLine != null -> {
                    rows += DiffRow(
                        kind = DiffRowKind.REMOVED,
                        leftNumber = sourceIndex + 1,
                        rightNumber = null,
                        left = listOf(RenderedDiffSegment(sourceLine, false)),
                        right = null,
                        hunkIndex = hunkIndex,
                        hunkStart = first,
                    )
                    sourceIndex++
                }
                targetLine != null -> {
                    rows += DiffRow(
                        kind = DiffRowKind.ADDED,
                        leftNumber = null,
                        rightNumber = targetIndex + 1,
                        left = null,
                        right = listOf(RenderedDiffSegment(targetLine, false)),
                        hunkIndex = hunkIndex,
                        hunkStart = first,
                    )
                    targetIndex++
                }
            }
            first = false
        }
    }
    while (sourceIndex < sourceLines.size) {
        context(sourceLines[sourceIndex])
        sourceIndex++
        targetIndex++
    }
    return rows
}

/** A hunk's first cell on one side — what a right click in a test lands on. */
internal fun diffHunkCellTag(hunkIndex: Int, left: Boolean) = "diff-hunk-$hunkIndex-${if (left) "left" else "right"}"

/** Row index of a hunk's first row, so a navigator can scroll the pair of columns to it. */
internal fun hunkRowIndex(rows: List<DiffRow>, hunkIndex: Int): Int =
    rows.indexOfFirst { it.hunkIndex == hunkIndex && it.hunkStart }.coerceAtLeast(0)

internal fun diffDisplayLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val body = if (text.endsWith('\n')) text.dropLast(1) else text
    return if (body.isEmpty()) listOf("") else body.split('\n')
}

/**
 * The reading surface of a comparison: two columns of the same height, one scroll, and a gutter that
 * only speaks where something actually differs. Lines wrap instead of scrolling sideways — a row has
 * to stay opposite its counterpart, and two independent horizontal offsets would break exactly that.
 */
@Composable
internal fun SideBySideDiff(
    rows: List<DiffRow>,
    listState: LazyListState,
    leftTitle: String,
    rightTitle: String,
    modifier: Modifier = Modifier,
    gutterWidth: Dp = 50.dp,
    /** The hunk drawn as picked — the one a context menu or a dialog is currently about. */
    selectedHunk: Int? = null,
    /**
     * A right press on a cell: the row's hunk (`null` on an unchanged line) and whether the left side
     * was pressed. Reported without consuming, so a context menu around the diff still opens.
     */
    onSecondaryPress: ((hunkIndex: Int?, left: Boolean) -> Unit)? = null,
    gutter: @Composable (hunkIndex: Int) -> Unit = {},
) {
    val extras = RuleblendTheme.extraColors
    val selectionColor = MaterialTheme.colorScheme.primary
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            DiffColumnTitle(leftTitle, Modifier.weight(1f))
            Box(Modifier.width(gutterWidth))
            DiffColumnTitle(rightTitle, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexedRows(rows) { row ->
                    val selected = selectedHunk != null && row.hunkIndex == selectedHunk
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .then(if (selected) Modifier.selectedHunk(selectionColor) else Modifier),
                    ) {
                        DiffCell(
                            number = row.leftNumber,
                            segments = row.left,
                            text = extras.danger,
                            mat = extras.dangerBackground,
                            highlight = row.kind != DiffRowKind.CONTEXT,
                            modifier = Modifier.weight(1f).hunkCellTag(row, left = true)
                                .secondaryPress(row.hunkIndex, left = true, onSecondaryPress),
                        )
                        Box(
                            Modifier.width(gutterWidth).fillMaxHeight(),
                            // Top, not centre: a hunk whose lines wrap is tall, and its buttons belong
                            // beside the line the hunk starts on.
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (row.hunkStart && row.hunkIndex != null) gutter(row.hunkIndex)
                        }
                        DiffCell(
                            number = row.rightNumber,
                            segments = row.right,
                            text = extras.success,
                            mat = extras.successBackground,
                            highlight = row.kind != DiffRowKind.CONTEXT,
                            modifier = Modifier.weight(1f).hunkCellTag(row, left = false)
                                .secondaryPress(row.hunkIndex, left = false, onSecondaryPress),
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.padding(vertical = 2.dp),
                style = defaultScrollbarStyle(),
            )
        }
    }
}

/**
 * A picked hunk: a tint over the diff mats plus a bar down its leading edge. The bar is what survives
 * on red and green rows, where a tint alone would read as one more shade of change.
 */
private fun Modifier.selectedHunk(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color.copy(alpha = 0.10f))
    drawRect(color, size = Size(3.dp.toPx(), size.height))
}

private fun Modifier.hunkCellTag(row: DiffRow, left: Boolean): Modifier =
    if (row.hunkStart && row.hunkIndex != null) testTag(diffHunkCellTag(row.hunkIndex, left)) else this

private fun Modifier.secondaryPress(
    hunkIndex: Int?,
    left: Boolean,
    callback: ((hunkIndex: Int?, left: Boolean) -> Unit)?,
): Modifier = if (callback == null) this else pointerInput(hunkIndex, left, callback) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) callback(hunkIndex, left)
        }
    }
}

/** Keeps the item key stable while letting the row list be rebuilt on every keystroke. */
private inline fun LazyListScope.itemsIndexedRows(
    rows: List<DiffRow>,
    crossinline row: @Composable (DiffRow) -> Unit,
) {
    items(rows.size) { index -> row(rows[index]) }
}

/**
 * The gutter control. Deliberately not one of the compact buttons: two of those do not fit a gutter
 * narrow enough to leave the text its width, and a single glyph needs no button metrics.
 */
@Composable
internal fun DiffTransferButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    }
    Text(
        glyph,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(enabled = enabled, onClick = onClick)
            .width(22.dp)
            .padding(vertical = 1.dp),
    )
}

@Composable
private fun DiffColumnTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = RuleblendTheme.extraColors.faint,
        modifier = modifier.padding(start = DiffNumberWidth + 6.dp, bottom = 2.dp),
    )
}

private val DiffNumberWidth = 30.dp

/**
 * One side of one row. A side with no line keeps its slot and says so with a flat fill, which is what
 * makes an addition read as an addition rather than as a line that moved.
 */
@Composable
private fun DiffCell(
    number: Int?,
    segments: List<RenderedDiffSegment>?,
    text: Color,
    mat: Color,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val absent = segments == null
    val background = when {
        absent -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        highlight -> mat
        else -> Color.Transparent
    }
    Row(modifier.fillMaxHeight().background(background)) {
        Text(
            number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
            color = RuleblendTheme.extraColors.faint,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(DiffNumberWidth).padding(end = 4.dp),
        )
        Text(
            annotatedDiffLine(segments.orEmpty(), if (highlight) text.copy(alpha = 0.22f) else Color.Transparent),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = if (highlight && !absent) text else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
    }
}

private fun annotatedDiffLine(segments: List<RenderedDiffSegment>, changedMat: Color): AnnotatedString =
    buildAnnotatedString {
        segments.forEach { segment ->
            if (segment.changed) pushStyle(SpanStyle(background = changedMat))
            append(segment.text)
            if (segment.changed) pop()
        }
    }
