package dev.ruleblend.app.theme

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import kotlin.math.roundToInt

/**
 * A fixed-width panel with a draggable right edge. The panel resizes live during the drag from a
 * local copy of [width], so no per-pixel callback fires into the caller; [onWidthChange] is invoked
 * once when the drag ends, giving the caller a single point to persist the new width.
 *
 * The divider is the only draggable region — the panel content keeps its own input. Clamps to
 * [min]/[max] so a stray drag cannot collapse the panel below a usable width.
 */
@Composable
fun SplitPane(
    width: Int,
    onWidthChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 140,
    max: Int = 480,
    /** Put the draggable edge on the leading side, for a panel that sits at the right of the window. */
    dividerAtStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Live width during an active drag; resets to the persisted value when no drag is in progress.
    var liveWidth by remember(width) { mutableStateOf(width.toFloat()) }
    Row(modifier.fillMaxHeight()) {
        // The grab strip overlays the pane's draggable edge. It stays large enough to drag without
        // reserving a gutter on either side of the visible line.
        Box(Modifier.width(liveWidth.dp).fillMaxHeight()) {
            content()
            ResizableDivider(
                // Dragging a right-hand panel's leading edge leftwards widens it, so the delta flips.
                onDrag = { delta ->
                    val step = if (dividerAtStart) -delta else delta
                    liveWidth = (liveWidth + step).coerceIn(min.toFloat(), max.toFloat())
                },
                onDragEnd = { onWidthChange(liveWidth.roundToInt()) },
                modifier = Modifier.align(
                    if (dividerAtStart) androidx.compose.ui.Alignment.CenterStart
                    else androidx.compose.ui.Alignment.CenterEnd,
                ),
            )
        }
    }
}

/** 1-dp divider line with a wider invisible grab strip beside it; highlights while dragged. */
@Composable
private fun ResizableDivider(onDrag: (Float) -> Unit, onDragEnd: () -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    // `pointerInput(Unit)` starts its gesture loop once and keeps the lambdas it captured then. The
    // pane's live width lives in a `remember(width)` that is replaced every time the persisted width
    // changes, so a captured callback would keep writing to the state object of an earlier drag —
    // the pane would then move only once the drag ended. Reading them through the current value
    // keeps every drag talking to the width the pane actually renders.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // A wider invisible strip overlays the content immediately before the line.
    Box(
        modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        currentOnDragEnd()
                    },
                    onDragCancel = {
                        dragging = false
                        currentOnDragEnd()
                    },
                ) { _, dragAmount -> currentOnDrag(dragAmount.x) }
            },
    )
    VerticalDivider(
        color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = modifier.width(1.dp).fillMaxHeight(),
    )
}
