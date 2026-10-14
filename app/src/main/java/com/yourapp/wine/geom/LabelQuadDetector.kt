package com.yourapp.wine.geom

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot

/** Phase-2 geometric heuristic, not a fabricated label-detection model. Text-hull fallback is separate. */
class LabelQuadDetector {
    /** Detects on <=640px working grayscale, returning four ordered ORIGINAL-capture pixel corners. */
    fun detect(bitmap: Bitmap, geom: FrameGeom): List<PointF>? {
        val rgba = Mat(); val gray = Mat(); val small = Mat(); val edges = Mat(); val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            val scale = minOf(1.0, 640.0 / maxOf(bitmap.width, bitmap.height))
            Imgproc.resize(gray, small, Size(bitmap.width * scale, bitmap.height * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            gray.release()
            Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.6)
            Imgproc.Canny(small, edges, 40.0, 120.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            try { Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel) } finally { kernel.release() }
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val candidates = contours.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(40).mapNotNull { contour ->
                val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * .02, true)
                    val points = approx.toArray()
                    if (points.size != 4) return@mapNotNull null
                    val integer = MatOfPoint(*points)
                    try { if (!Imgproc.isContourConvex(integer)) return@mapNotNull null } finally { integer.release() }
                    val ordered = QuadOrder.clockwise(points.map { PointF(it.x.toFloat(), it.y.toFloat()) })
                    val area = abs(Imgproc.contourArea(approx)) / (small.cols().toDouble() * small.rows())
                    if (area !in .045.. .92) return@mapNotNull null
                    val width = (distance(ordered[0], ordered[1]) + distance(ordered[2], ordered[3])) / 2
                    val height = (distance(ordered[0], ordered[3]) + distance(ordered[1], ordered[2])) / 2
                    if (width / height !in .25f..3.0f) return@mapNotNull null
                    val angleError = ordered.indices.maxOf { i ->
                        val p = ordered[i]; val a = ordered[(i + 3) % 4]; val b = ordered[(i + 1) % 4]
                        abs((a.x - p.x) * (b.x - p.x) + (a.y - p.y) * (b.y - p.y)) /
                            (distance(a, p) * distance(b, p)).coerceAtLeast(1f)
                    }
                    if (angleError > .75f) return@mapNotNull null
                    val cx = ordered.map { it.x }.average() / small.cols()
                    val cy = ordered.map { it.y }.average() / small.rows()
                    val centerPenalty = hypot(cx - .5, cy - .5)
                    val score = area * (1.0 - angleError) * (1.0 - centerPenalty * .4)
                    score to ordered.map { p -> PointF(p.x * geom.origW / small.cols(), p.y * geom.origH / small.rows()) }
                } finally { curve.release(); approx.release() }
            }
            return candidates.maxByOrNull { it.first }?.second
        } finally {
            contours.forEach { it.release() }
            listOf(rgba, gray, small, edges, hierarchy).forEach { it.release() }
        }
    }

    private fun distance(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)
}
