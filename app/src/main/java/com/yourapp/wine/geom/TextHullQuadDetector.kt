package com.yourapp.wine.geom

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.ocr.RawToken
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

class TextHullQuadDetector {
    /** Cheap-probe polygons in WORKING pixels -> dense text hull/min-area quad in ORIGINAL-capture pixels. */
    fun detect(tokens: List<RawToken>, geom: FrameGeom): List<PointF>? {
        val samples = tokens.filter { it.score >= .6f && it.polygon.size >= 3 }.map { token ->
            RectF(token.polygon.minOf { it.x }, token.polygon.minOf { it.y }, token.polygon.maxOf { it.x },
                token.polygon.maxOf { it.y }) to token.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        }.filter { (box, _) -> box.width() > 2 && box.height() > 2 }
        val boxes = samples.map { it.first }
        if (boxes.size < 2) return null
        val parent = IntArray(boxes.size) { it }
        fun root(i: Int): Int { var n = i; while (parent[n] != n) { parent[n] = parent[parent[n]]; n = parent[n] }; return n }
        val medianHeight = boxes.map { it.height() }.sorted()[boxes.size / 2]
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            val dx = kotlin.math.abs(a.centerX() - b.centerX())
            val dy = kotlin.math.abs(a.centerY() - b.centerY())
            if ((hypot(dx, dy) <= .16f * maxOf(geom.workW, geom.workH)) ||
                (dx <= .25f * geom.workW && dy <= 5f * medianHeight)) parent[root(i)] = root(j)
        }
        val dense = boxes.indices.groupBy(::root).values.filter { group -> group.size >= 2 && group.sumOf { samples[it].second } >= 3 }.maxByOrNull { group ->
            val area = (group.maxOf { boxes[it].right } - group.minOf { boxes[it].left }) *
                (group.maxOf { boxes[it].bottom } - group.minOf { boxes[it].top })
            val ink = group.sumOf { (boxes[it].width() * boxes[it].height()).toDouble() }
            ink / area.coerceAtLeast(1f) * kotlin.math.sqrt(group.size.toDouble())
        } ?: return null
        val points = dense.flatMap { i -> boxes[i].let { b -> listOf(Point(b.left.toDouble(), b.top.toDouble()),
            Point(b.right.toDouble(), b.top.toDouble()), Point(b.right.toDouble(), b.bottom.toDouble()), Point(b.left.toDouble(), b.bottom.toDouble())) } }
        val contour = MatOfPoint(*points.toTypedArray()); val hullIndices = MatOfInt()
        try {
            Imgproc.convexHull(contour, hullIndices)
            val hull = MatOfPoint2f(*hullIndices.toArray().map { points[it] }.toTypedArray())
            try {
                val rectangle = Imgproc.minAreaRect(hull)
                if (rectangle.size.width < 16 || rectangle.size.height < 16) return null
                val corners = Array(4) { Point() }; rectangle.points(corners)
                val center = rectangle.center
                // Symmetric, explicit expansion around the text hull, bounded to stay inside the frame.
                val factors = corners.flatMap { p -> listOf(
                    if (p.x > center.x) (geom.workW - center.x) / (p.x - center.x) else center.x / (center.x - p.x),
                    if (p.y > center.y) (geom.workH - center.y) / (p.y - center.y) else center.y / (center.y - p.y)) }
                val factor = minOf(1.12, factors.minOrNull() ?: 1.0)
                if (factor < 1.0) return null
                val quad = corners.map { p -> PointF(((center.x + (p.x - center.x) * factor) * geom.origW / geom.workW).toFloat(),
                    ((center.y + (p.y - center.y) * factor) * geom.origH / geom.workH).toFloat()) }
                return QuadOrder.clockwise(quad)
            } finally { hull.release() }
        } finally { contour.release(); hullIndices.release() }
    }
}
