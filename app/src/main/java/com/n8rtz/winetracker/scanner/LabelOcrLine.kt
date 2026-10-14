package com.n8rtz.winetracker.scanner

import kotlin.math.roundToInt

/**
 * A rectangle in the coordinate space of the bitmap one OCR pass was given.
 *
 * ML Kit reports a `Rect` per line; it is converted into this plain box at the ML Kit boundary, so
 * crop geometry can be reasoned about, mapped between passes and tested without the SDK.
 */
internal data class LabelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = right > left && bottom > top
    val longestEdge: Int get() = maxOf(width, height)

    fun union(other: LabelBox): LabelBox = LabelBox(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )

    /** Grows the box by [fraction] of its longer edge, so text next to a box edge is not clipped. */
    fun pad(fraction: Float): LabelBox {
        val margin = (longestEdge * fraction).roundToInt().coerceAtLeast(0)
        return LabelBox(left - margin, top - margin, right + margin, bottom + margin)
    }

    /**
     * Grows a small box around its own centre until its longer edge is [minEdge] pixels, so a crop
     * never magnifies so far that the recognizer is handed a few pixels of a letter.
     */
    fun growToMinEdge(minEdge: Int): LabelBox {
        if (longestEdge >= minEdge) return this
        val growX = (minEdge - width).coerceAtLeast(0) / 2
        val growY = (minEdge - height).coerceAtLeast(0) / 2
        return LabelBox(left - growX, top - growY, right + growX, bottom + growY)
    }

    fun clampTo(width: Int, height: Int): LabelBox = LabelBox(
        left = left.coerceIn(0, width),
        top = top.coerceIn(0, height),
        right = right.coerceIn(0, width),
        bottom = bottom.coerceIn(0, height)
    )

    fun contains(other: LabelBox): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/** One line an OCR pass read, with where it sat on the bitmap that pass was given. */
internal data class LabelOcrLine(val text: String, val box: LabelBox? = null, val blockIndex: Int = 0)

/**
 * One OCR pass of a scan: the text it read, the lines with their boxes, and how much of the label the
 * pass looked at.
 *
 * A full view is a whole-label read (the frame or a centre crop): it defines the reading order of the
 * label. A targeted pass re-reads one magnified region to settle a word that the full views read
 * differently, so it may correct a line but must never reorder or extend the label.
 */
internal data class LabelOcrHypothesis(
    val text: String,
    val lines: List<LabelOcrLine> = emptyList(),
    val role: Role = Role.FULL_VIEW
) {
    enum class Role { FULL_VIEW, TARGETED }
}
