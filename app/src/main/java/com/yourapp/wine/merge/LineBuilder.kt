package com.yourapp.wine.merge

import android.graphics.RectF
import com.yourapp.wine.ocr.MergedToken
import kotlin.math.abs

/** A line and its evidence in normalized rectified-label space, never full-scene y zones. */
data class LabelLine(val text: String, val tokens: List<MergedToken>, val bboxRect: RectF) {
    val vote: Float get() = tokens.sumOf { (it.vote * it.canonical.length).toDouble() }
        .div(tokens.sumOf { it.canonical.length }.coerceAtLeast(1)).toFloat()
}

class LineBuilder {
    /** Groups normalized rectified boxes by overlap and median token height, then sorts y and x. */
    fun build(tokens: List<MergedToken>): List<LabelLine> {
        if (tokens.isEmpty()) return emptyList()
        val medianHeight = median(tokens.map { it.bboxRect.height() })
        val lines = mutableListOf<MutableList<MergedToken>>()
        tokens.sortedWith(compareBy<MergedToken> { it.bboxRect.centerY() }.thenBy { it.bboxRect.left })
            .forEach { token ->
                val best = lines.filter { line ->
                    val box = representative(line)
                    BoxMath.verticalOverlap(box, token.bboxRect) >= 0.5f &&
                        abs(box.centerY() - token.bboxRect.centerY()) <= 0.45f * medianHeight
                }.minByOrNull { abs(representative(it).centerY() - token.bboxRect.centerY()) }
                if (best == null) lines.add(mutableListOf(token)) else best.add(token)
            }
        return lines.map { group ->
            val ordered = group.sortedBy { it.bboxRect.left }
            LabelLine(ordered.joinToString(" ") { it.canonical }, ordered, BoxMath.union(ordered.map { it.bboxRect }))
        }.sortedWith(compareBy<LabelLine> { it.bboxRect.centerY() }.thenBy { it.bboxRect.left })
    }

    private fun representative(tokens: List<MergedToken>) = RectF(tokens.minOf { it.bboxRect.left },
        median(tokens.map { it.bboxRect.top }), tokens.maxOf { it.bboxRect.right },
        median(tokens.map { it.bboxRect.bottom }))

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    }
}
