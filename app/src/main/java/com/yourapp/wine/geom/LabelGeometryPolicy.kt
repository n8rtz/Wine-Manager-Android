package com.yourapp.wine.geom

import android.graphics.PointF
import com.yourapp.wine.ocr.RawToken
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Dimensions are rectified-label pixels, bounded independently of the source image's original dimensions. */
data class RectifiedSize(val width: Int, val height: Int)

object LabelGeometryPolicy {
    const val MIN_LABEL_AREA_FRACTION = .12

    /** Natural WORKING-pixel label dimensions -> output pixels; enlarge crops by at most 2x, width <=1280/edge <=1920. */
    fun rectifiedSize(naturalW: Float, naturalH: Float): RectifiedSize {
        require(naturalW.isFinite() && naturalH.isFinite() && naturalW >= 16f && naturalH >= 16f) { "Degenerate label quad" }
        val scale = minOf(2f, 1280f / naturalW, 1920f / maxOf(naturalW, naturalH))
        return RectifiedSize((naturalW * scale).roundToInt().coerceIn(16, 1280),
            (naturalH * scale).roundToInt().coerceIn(16, 1920))
    }

    /** Quad pixels and frame pixels in the SAME space -> frame-area fraction. */
    fun areaFraction(quad: List<PointF>, frameW: Int, frameH: Int): Double {
        require(quad.size == 4 && frameW > 0 && frameH > 0)
        if (quad.any { !it.x.isFinite() || !it.y.isFinite() }) return 0.0
        return abs(quad.indices.sumOf { i ->
            val a = quad[i]; val b = quad[(i + 1) % 4]
            a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }) / (2.0 * frameW * frameH)
    }

    /** SAME-space quad/frame pixels. A short wide band may be only the readable label footer.
     * Decline rather than invent an upper boundary; the caller retains the full-frame fallback.
     * Deliberately conservative: a genuine wide label may also fall back, never lose its pixels.
     */
    fun isPartialBand(quad: List<PointF>, frameH: Int): Boolean {
        require(frameH > 0)
        if (quad.size != 4 || quad.any { !it.x.isFinite() || !it.y.isFinite() }) return true
        val width = quad.maxOf { it.x } - quad.minOf { it.x }
        val height = quad.maxOf { it.y } - quad.minOf { it.y }
        return height < .5f * frameH && width > 1.2f * height
    }

    /** WORKING text polygons -> ORIGINAL proposal, aligned with text baselines rather than a tapered group's hull. */
    fun textQuad(tokens: List<RawToken>, workW: Int, workH: Int, origW: Int, origH: Int): List<PointF>? {
        if (tokens.isEmpty()) return null
        val points = tokens.flatMap { it.polygon }
        if (points.size < 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val angles = tokens.mapNotNull { token ->
            if (token.polygon.size != 4) return@mapNotNull null
            val a = token.polygon[0]; val b = token.polygon[1]
            if (kotlin.math.hypot(b.x - a.x, b.y - a.y) < 2) return@mapNotNull null
            var angle = atan2((b.y-a.y).toDouble(), (b.x-a.x).toDouble())
            while (angle > Math.PI/2) angle -= Math.PI
            while (angle < -Math.PI/2) angle += Math.PI
            angle.takeIf { abs(it) <= Math.PI/6 }
        }.sorted()
        val angle = angles.getOrNull(angles.size / 2) ?: 0.0
        val c = cos(angle); val s = sin(angle)
        val u = points.map { it.x * c + it.y * s }
        val v = points.map { -it.x * s + it.y * c }
        val l = u.min(); val r = u.max(); val t = v.min(); val b = v.max()
        if (r-l < 16 || b-t < 16) return null
        fun point(x: Double, y: Double) = PointF((x*c-y*s).toFloat(), (x*s+y*c).toFloat())
        return expandedTextQuad(listOf(point(l,t), point(r,t), point(r,b), point(l,b)), workW, workH, origW, origH)
    }

    /** WORKING oriented-rectangle corners -> padded ORIGINAL quad; a tiny text fragment is not a label boundary. */
    fun expandedTextQuad(corners: List<PointF>, workW: Int, workH: Int, origW: Int, origH: Int): List<PointF>? {
        require(workW > 0 && workH > 0 && origW > 0 && origH > 0)
        if (corners.size != 4 || corners.any { !it.x.isFinite() || !it.y.isFinite() ||
                it.x !in 0f..workW.toFloat() || it.y !in 0f..workH.toFloat() }) return null
        val cx = corners.map { it.x.toDouble() }.average()
        val cy = corners.map { it.y.toDouble() }.average()
        var factor = 1.35 // Leave room for glyphs/whole lines the localization recognizer missed.
        for (p in corners) {
            val dx = p.x - cx; val dy = p.y - cy
            if (dx > 0) factor = minOf(factor, (workW - cx) / dx)
            if (dx < 0) factor = minOf(factor, -cx / dx)
            if (dy > 0) factor = minOf(factor, (workH - cy) / dy)
            if (dy < 0) factor = minOf(factor, -cy / dy)
        }
        if (factor < 1.0) return null
        val quad = QuadOrder.clockwise(corners.map { p ->
            PointF(((cx + (p.x - cx) * factor) * origW / workW).toFloat(),
                ((cy + (p.y - cy) * factor) * origH / workH).toFloat())
        })
        // Unlike a contour boundary, a small OCR envelope is not proof that the actual label is too small.
        // Decline the proposal instead of rejecting an otherwise readable capture as "Move closer".
        return quad.takeIf { areaFraction(it, origW, origH) >= MIN_LABEL_AREA_FRACTION && !isPartialBand(it, origH) }
    }
}
