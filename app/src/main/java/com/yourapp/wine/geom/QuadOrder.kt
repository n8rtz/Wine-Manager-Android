package com.yourapp.wine.geom

import android.graphics.PointF
import kotlin.math.atan2

object QuadOrder {
    /** Orders four original/working pixel corners TL,TR,BR,BL; the caller specifies the image space. */
    fun clockwise(points: List<PointF>): List<PointF> {
        require(points.size == 4)
        val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }
}
