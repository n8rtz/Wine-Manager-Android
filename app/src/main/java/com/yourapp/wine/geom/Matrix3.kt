package com.yourapp.wine.geom

import android.graphics.PointF
import kotlin.math.abs

/** Immutable row-major homography between documented continuous image-pixel spaces. */
class Matrix3(val values: List<Double>) {
    init { require(values.size == 9 && values.all { it.isFinite() }) }

    /** Maps a point from this homography's input pixel space to its output pixel space. */
    fun map(point: PointF): PointF {
        val m = values
        val z = m[6] * point.x + m[7] * point.y + m[8]
        require(abs(z) > 1e-10) { "Projective point at infinity" }
        return PointF(((m[0] * point.x + m[1] * point.y + m[2]) / z).toFloat(),
            ((m[3] * point.x + m[4] * point.y + m[5]) / z).toFloat())
    }

    /** Reverses the documented pixel-space warp; singular crops are rejected, never OCRed unmapped. */
    fun inverse(): Matrix3 {
        val m = values
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(determinant) > 1e-12) { "Singular label warp" }
        return Matrix3(listOf(e * i - f * h, c * h - b * i, b * f - c * e,
            f * g - d * i, a * i - c * g, c * d - a * f,
            d * h - e * g, b * g - a * h, a * e - b * d).map { it / determinant })
    }

    /** Composition maps rhs input pixels through rhs, then this matrix's input/output pixel spaces. */
    operator fun times(rhs: Matrix3): Matrix3 = Matrix3(List(9) { n ->
        val row = n / 3; val col = n % 3
        (0..2).sumOf { k -> values[row * 3 + k] * rhs.values[k * 3 + col] }
    })

    companion object {
        /** Explicit axis scaling between original, working or rectified pixel spaces, including rounding. */
        fun scale(x: Double, y: Double): Matrix3 = Matrix3(listOf(x, 0.0, 0.0, 0.0, y, 0.0, 0.0, 0.0, 1.0))
    }
}
