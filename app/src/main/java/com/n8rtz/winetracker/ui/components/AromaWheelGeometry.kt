package com.n8rtz.winetracker.ui.components

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure math for the radial aroma wheel, kept free of Android/Compose types so it
 * can be unit tested on the JVM. Angles are measured clockwise from 12 o'clock
 * (the wheel's segment origin); Canvas `drawArc` conversions happen at the call
 * site.
 */
object AromaWheelGeometry {

    /**
     * Clockwise angle in degrees from 12 o'clock to the point ([x], [y]) relative
     * to ([centerX], [centerY]). Returns 0 at the top, 90 right, 180 bottom, 270 left.
     */
    fun clockwiseAngleFromTopDeg(x: Float, y: Float, centerX: Float, centerY: Float): Float {
        val dx = (x - centerX).toDouble()
        val dy = (y - centerY).toDouble()
        if (dx == 0.0 && dy == 0.0) return 0f
        val deg = Math.toDegrees(atan2(dx, -dy))
        return ((deg + 360.0) % 360.0).toFloat()
    }

    /** Euclidean distance from the wheel center to the point ([x], [y]). */
    fun radius(x: Float, y: Float, centerX: Float, centerY: Float): Float =
        hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()

    /**
     * Index (0-based) of the segment under [angleDeg] when [segmentCount] equal
     * segments fill the circle starting at 12 o'clock. Wraps 360 to the last
     * segment; returns -1 when [segmentCount] is not positive.
     */
    fun segmentIndex(angleDeg: Float, segmentCount: Int): Int {
        if (segmentCount <= 0) return -1
        val clamped = ((angleDeg % 360f) + 360f) % 360f
        return (clamped / (360f / segmentCount)).toInt().coerceIn(0, segmentCount - 1)
    }

    /** Mid-angle (clockwise from 12 o'clock) of segment [index]. */
    fun segmentMidAngleDeg(index: Int, segmentCount: Int): Float =
        (index + 0.5f) * (360f / segmentCount)

    /** Converts a clockwise-from-top angle to a Canvas `drawArc` start angle at 3 o'clock. */
    fun canvasStartAngleDeg(clockwiseFromTopDeg: Float): Float = clockwiseFromTopDeg - 90f

    /** Screen-space unit-vector direction of a clockwise-from-top angle. */
    fun directionDeg(angleDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        return Math.sin(rad).toFloat() to -Math.cos(rad).toFloat()
    }

    // --- ARGB shading helpers (plain longs; see AromaTaxonomy) ---

    /** Blends an ARGB color toward white by [fraction] (0..1). */
    fun lightenArgb(argb: Long, fraction: Float): Long =
        blendArgb(argb, 0xFF, fraction)

    /** Blends an ARGB color toward black by [fraction] (0..1). */
    fun darkenArgb(argb: Long, fraction: Float): Long =
        blendArgb(argb, 0x00, fraction)

    private fun blendArgb(argb: Long, targetChannel: Long, fraction: Float): Long {
        val clamped = fraction.coerceIn(0f, 1f)
        fun blend(channel: Long): Long {
            val moved = channel + ((targetChannel - channel) * clamped).toLong()
            return moved.coerceIn(0L, 255L)
        }
        val a = (argb ushr 24) and 0xFF
        val r = blend((argb ushr 16) and 0xFF)
        val g = blend((argb ushr 8) and 0xFF)
        val b = blend(argb and 0xFF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Deterministic shade of a family color for leaf segment [index]; cycles
     * through base / light / dark / lighter so adjacent wedges stay distinct.
     */
    fun shadeArgbAt(argb: Long, index: Int, cycleSize: Int = 4): Long {
        val step = ((index % cycleSize) + cycleSize) % cycleSize
        return when (step) {
            0 -> argb
            1 -> lightenArgb(argb, 0.32f)
            2 -> darkenArgb(argb, 0.24f)
            else -> lightenArgb(argb, 0.55f)
        }
    }

    /** Relative luminance (0..1) of an opaque ARGB color. */
    fun relativeLuminance(argb: Long): Float {
        fun channel(value: Long): Float {
            val c = (value and 0xFF).toFloat() / 255f
            return if (c <= 0.03928f) c / 12.92f else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
        }
        val r = channel((argb ushr 16) and 0xFF)
        val g = channel((argb ushr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** Black or white ARGB, whichever reads better on [backgroundArgb]. */
    fun readableTextArgb(backgroundArgb: Long): Long =
        if (relativeLuminance(backgroundArgb) >= 0.5f) 0xFF141414 else 0xFFFDFDFD
}
