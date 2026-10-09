package dev.ruleblend.app.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Widths a three-column surface actually gets, after the window has been paid for. A `null` pane is
 * dropped: the window is too narrow to show it without starving the centre.
 */
data class PaneFit(val leading: Dp?, val trailing: Dp?)

/** No centre column narrower than this — below it the file preview stops being readable. */
val CentreMinWidth: Dp = 360.dp

/** A side pane below this is a scroll bar with a hint of text, so it is dropped instead. */
val PaneMinWidth: Dp = 140.dp

/**
 * Fits two side panes into [available], keeping [centre] for the middle column.
 *
 * Panes shrink before they disappear, and they disappear from the leading side first: the leading
 * pane is navigation (place list, facets), reachable through the rail and ⌘K, while the trailing
 * pane is the working surface (library palette, inspector) that the centre column has no substitute
 * for. Requested widths are never exceeded — a narrow window shrinks panes, it does not grow them.
 */
fun fitPanes(
    available: Dp,
    leading: Dp,
    trailing: Dp,
    centre: Dp = CentreMinWidth,
    paneMin: Dp = PaneMinWidth,
): PaneFit {
    val budget = available - centre
    // A pane asked for less than its minimum is not on the surface at all (a hidden palette, a
    // screen without an inspector), so it takes part in nothing below.
    val wantsLeading = leading >= paneMin
    val wantsTrailing = trailing >= paneMin
    if (!wantsLeading || !wantsTrailing) {
        val only = if (wantsLeading) leading else if (wantsTrailing) trailing else return PaneFit(null, null)
        val fitted = if (budget >= paneMin) minOf(budget, only) else null
        return if (wantsLeading) PaneFit(fitted, null) else PaneFit(null, fitted)
    }
    if (budget >= leading + trailing) return PaneFit(leading, trailing)
    if (budget >= paneMin * 2) {
        val share = leading / (leading + trailing)
        val head = (budget * share).coerceIn(paneMin, leading)
        val tail = (budget - head).coerceIn(paneMin, trailing)
        // Whatever the trailing cap left over goes back to the leading pane, not to the centre.
        return PaneFit((budget - tail).coerceIn(paneMin, leading), tail)
    }
    if (budget >= paneMin) return PaneFit(null, minOf(budget, trailing))
    return PaneFit(null, null)
}

/**
 * The widest a draggable pane may become while the other pane keeps [other] and the centre keeps
 * [centre]. Feeds the resize handle so a drag cannot push the centre out of the window.
 */
fun paneMaxWidth(available: Dp, other: Dp, centre: Dp = CentreMinWidth, paneMin: Dp = PaneMinWidth): Dp =
    (available - other - centre).coerceAtLeast(paneMin)
