package com.yourapp.wine.merge

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object BoxMath {
    /** Input and output are in the same normalized rectified-label space. */
    fun union(boxes: List<RectF>): RectF = if (boxes.isEmpty()) RectF() else RectF(
        boxes.minOf { it.left }, boxes.minOf { it.top },
        boxes.maxOf { it.right }, boxes.maxOf { it.bottom })

    /** Both boxes must be in normalized rectified-label coordinates. */
    fun iou(a: RectF, b: RectF): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val total = a.width() * a.height() + b.width() * b.height() - intersection
        return if (total > 0f) intersection / total else 0f
    }

    /** Returns center L2 distance in normalized rectified-label coordinates. */
    fun centerDistance(a: RectF, b: RectF): Float =
        hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY())

    /** Returns overlap relative to the shorter box, in normalized rectified-label coordinates. */
    fun verticalOverlap(a: RectF, b: RectF): Float {
        val height = min(a.height(), b.height())
        return if (height > 0f) max(0f, min(a.bottom, b.bottom) - max(a.top, b.top)) / height else 0f
    }
}
