package com.n8rtz.winetracker.ui.components

/**
 * Pure drag-reorder maths for a horizontal row of chips of uneven widths.
 *
 * Given the index currently being dragged and the accumulated pixel
 * [offsetPx] of the drag, returns the index the chip should occupy once it
 * has crossed a neighbour's halfway point, together with the offset
 * re-expressed relative to that new index, so the drag continues seamlessly
 * across swaps. Pure Kotlin (no Android/Compose types) so it can be unit
 * tested on the JVM.
 */
internal fun aromaChipDragTarget(
    fromIndex: Int,
    offsetPx: Float,
    count: Int,
    widthAt: (Int) -> Int
): Pair<Int, Float> {
    var index = fromIndex
    var offset = offsetPx
    if (fromIndex < 0 || fromIndex >= count || count == 0) return fromIndex to offsetPx
    while (true) {
        val next = index + 1
        if (offset > 0f && next in 0 until count) {
            val width = widthAt(next).coerceAtLeast(1)
            if (offset >= width / 2f) {
                index = next
                offset -= width.toFloat()
                continue
            }
        }
        val previous = index - 1
        if (offset < 0f && previous >= 0) {
            val width = widthAt(previous).coerceAtLeast(1)
            if (-offset >= width / 2f) {
                index = previous
                offset += width.toFloat()
                continue
            }
        }
        return index to offset
    }
}
