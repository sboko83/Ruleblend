package dev.ruleblend.app.place

/**
 * The list with the item at [from] moved to [to]. Both indices are clamped, so a drag that ran past
 * either end of the run lands on the end row instead of throwing.
 */
fun <T> List<T>.moveItem(from: Int, to: Int): List<T> {
    if (isEmpty()) return this
    val source = from.coerceIn(indices)
    val destination = to.coerceIn(indices)
    if (source == destination) return this
    val rest = toMutableList()
    val moved = rest.removeAt(source)
    rest.add(destination, moved)
    return rest
}

/**
 * The index a row dragged by [offset] pixels from position [from] lands on. Rows are measured, not
 * assumed uniform: a block with a drift notice is taller than a synced one, so a drag is resolved
 * against the rows it actually passes. A neighbour is passed once the drag covers more than half of
 * its height, which is where the gap visibly moves.
 *
 * [heights] are the row heights in current display order, including the gap between rows.
 */
fun dropTargetIndex(heights: List<Float>, from: Int, offset: Float): Int {
    if (from !in heights.indices) return from
    var index = from
    var left = offset
    // One direction per drag: overshooting a tall neighbour must not bounce the row back up.
    if (offset > 0) {
        while (index < heights.lastIndex && left > heights[index + 1] / 2) {
            left -= heights[index + 1]
            index++
        }
    } else {
        while (index > 0 && -left > heights[index - 1] / 2) {
            left += heights[index - 1]
            index--
        }
    }
    return index
}

/**
 * How far the dragged row must be pulled back after the list under it moved from [from] to [to], so
 * that the row stays under the pointer while its neighbours shift by their own heights.
 */
fun dragOffsetAfterMove(heights: List<Float>, from: Int, to: Int): Float {
    if (to == from) return 0f
    val range = if (to > from) (from + 1)..to else to until from
    val passed = range.fold(0f) { total, index -> total + heights.getOrElse(index) { 0f } }
    return if (to > from) passed else -passed
}
